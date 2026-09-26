package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.*;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 检索基金公告和定期报告中的原文片段，并给每个片段附上可定位的文档证据。不能代替净值或指标计算。
 * 参数不合法时返回可修正错误；检索失败时返回数据未就绪。缺少执行轨迹时直接抛出 IllegalStateException。
 */
public final class FundDocumentSearchTool {
    public static final String NAME = "search_fund_documents";
    private static final String MODEL_TOOL_NAME = "search_documents";
    private final KnowledgeSearchUseCase useCase;
    private final Validator validator;

    /**
     * 绑定知识检索用例和校验器。不在构造时拒绝空引用。
     */
    public FundDocumentSearchTool(KnowledgeSearchUseCase useCase, Validator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    /**
     * 按基金代码和问句检索文档。未指定文档类型时搜全部类型，未指定条数时取 6 条。
     * 没有命中时仍返回成功和空列表，不伪造引用。约束违反记录 INVALID_ARGUMENT；其他运行时失败记录 DOCUMENT_SEARCH_FAILED。
     */
    @Tool(name = MODEL_TOOL_NAME, description = "检索基金公告、招募说明书和定期报告中的条款、风险披露、投资策略及基金经理原文观点。不能用于计算净值、收益、波动率或回撤。")
    public FundToolEnvelope<DocumentSearchToolResult> search(SearchInput input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            FundToolSupport.validate(validator, input);
            var query = new KnowledgeSearchQuery(input.query(), new HashSet<>(input.fundCodes()),
                    input.documentTypes() == null ? Set.of() : input.documentTypes(), input.publishedAfter(),
                    input.publishedBefore(), input.topK() == null ? 6 : input.topK());
            KnowledgeSearchResult result = trace.call(call, () -> useCase.search(query));
            List<EvidenceReference> evidence = result.chunks().stream().map(hit -> evidence(hit.chunk())).toList();
            List<DocumentMatch> matches = result.chunks().stream().map(hit -> new DocumentMatch(hit.chunk().documentTitle(),
                    hit.chunk().documentType(), hit.chunk().publishedDate(), hit.chunk().pageStart(), hit.chunk().pageEnd(),
                    hit.chunk().headingPath(), excerpt(hit.chunk().content()),
                    "DOC:" + hit.chunk().documentId() + ":" + hit.chunk().versionId() + ":" + hit.chunk().chunkId(),
                    hit.channels())).toList();
            var toolResult = new DocumentSearchToolResult(result.retrievalId(), matches);
            trace.success(call, evidence, toolResult);
            return new FundToolEnvelope<>(NAME, "fund-doc-tools-v1", ToolResultStatus.SUCCESS, toolResult, evidence,
                    result.warnings(), null, null);
        } catch (ConstraintViolationException | IllegalArgumentException ex) {
            trace.failure(call, "INVALID_ARGUMENT");
            return new FundToolEnvelope<>(NAME, "fund-doc-tools-v1", ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "INVALID_ARGUMENT", FundToolSupport.safeMessage(ex));
        } catch (RuntimeException ex) {
            trace.failure(call, "DOCUMENT_SEARCH_FAILED");
            return new FundToolEnvelope<>(NAME, "fund-doc-tools-v1", ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "DOCUMENT_SEARCH_FAILED", FundToolSupport.safeMessage(ex));
        }
    }

    /**
     * 把文档切片收成文档证据。基金代码取排序后的第一个；切片没有基金代码时 fundCode 为空，不因此失败。
     */
    private EvidenceReference evidence(DocumentChunk c) {
        String id = "DOC:" + c.documentId() + ":" + c.versionId() + ":" + c.chunkId();
        String code = c.fundCodes().stream().sorted().findFirst().orElse(null);
        return EvidenceReference.document(id, code, c.sourceName(), c.documentId(), c.versionId(), c.chunkId(),
                c.documentTitle(), c.publishedDate(), c.pageStart(), c.pageEnd(), c.headingPath(), excerpt(c.content()), c.sourceUri());
    }

    /**
     * 去掉正文里除换行和制表符以外的控制字符，并截断到 1200 字。空正文返回空字符串，不抛出异常。
     */
    private String excerpt(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").trim();
        return cleaned.substring(0, Math.min(1200, cleaned.length()));
    }

    /**
     * 文档检索入参。至少一只、至多十只 6 位基金代码，问句不能为空白且不超过 500 字。
     * 文档类型和发布日期可空；topK 若提供则须在 1 到 10。构造器不执行这些约束。
     */
    public record SearchInput(@NotEmpty @Size(max = 10) List<@Pattern(regexp = "\\d{6}") String> fundCodes,
            @NotBlank @Size(max = 500) String query, Set<FundDocumentType> documentTypes, LocalDate publishedAfter,
            LocalDate publishedBefore, @Min(1) @Max(10) Integer topK) {}

    /**
     * 一次检索的标识和命中列表。命中为空表示没有可引用原文，不是工具失败。
     */
    public record DocumentSearchToolResult(String retrievalId, List<DocumentMatch> matches) {}

    /**
     * 单条文档命中。evidenceId 与返回信封中的文档证据编号一致，供模型引用原文。
     */
    public record DocumentMatch(String documentTitle, FundDocumentType documentType, LocalDate publishedDate, int pageStart,
            int pageEnd, String headingPath, String excerpt, String evidenceId, Set<String> retrievalChannels) {}
}
