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

/** 实现 FundDocumentSearchTool 所代表的 Agent 运行时职责。 */
public final class FundDocumentSearchTool {
    public static final String NAME="search_fund_documents";
    private static final String MODEL_TOOL_NAME="search_documents";
    private final KnowledgeSearchUseCase useCase;private final Validator validator;
    
    /** 执行该 Agent 运行时组件中的 FundDocumentSearchTool 操作。 */
    public FundDocumentSearchTool(KnowledgeSearchUseCase useCase,Validator validator){this.useCase=useCase;this.validator=validator;}
    @Tool(name=MODEL_TOOL_NAME,description="检索基金公告、招募说明书和定期报告中的条款、风险披露、投资策略及基金经理原文观点。不能用于计算净值、收益、波动率或回撤。")
    
    /** 执行该 Agent 运行时组件中的 search 操作。 */
    public FundToolEnvelope<DocumentSearchToolResult> search(SearchInput input,ToolContext context){AgentExecutionTrace trace=FundToolSupport.trace(context);var call=trace.begin(NAME,input);try{FundToolSupport.validate(validator,input);var query=new KnowledgeSearchQuery(input.query(),new HashSet<>(input.fundCodes()),input.documentTypes()==null?Set.of():input.documentTypes(),input.publishedAfter(),input.publishedBefore(),input.topK()==null?6:input.topK());KnowledgeSearchResult result=trace.call(call,()->useCase.search(query));List<EvidenceReference>evidence=result.chunks().stream().map(hit->evidence(hit.chunk())).toList();List<DocumentMatch>matches=result.chunks().stream().map(hit->new DocumentMatch(hit.chunk().documentTitle(),hit.chunk().documentType(),hit.chunk().publishedDate(),hit.chunk().pageStart(),hit.chunk().pageEnd(),hit.chunk().headingPath(),excerpt(hit.chunk().content()),"DOC:"+hit.chunk().documentId()+":"+hit.chunk().versionId()+":"+hit.chunk().chunkId(),hit.channels())).toList();var toolResult=new DocumentSearchToolResult(result.retrievalId(),matches);trace.success(call,evidence,toolResult);return new FundToolEnvelope<>(NAME,"fund-doc-tools-v1",ToolResultStatus.SUCCESS,toolResult,evidence,result.warnings(),null,null);}catch(ConstraintViolationException|IllegalArgumentException ex){trace.failure(call,"INVALID_ARGUMENT");return new FundToolEnvelope<>(NAME,"fund-doc-tools-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"INVALID_ARGUMENT",FundToolSupport.safeMessage(ex));}catch(RuntimeException ex){trace.failure(call,"DOCUMENT_SEARCH_FAILED");return new FundToolEnvelope<>(NAME,"fund-doc-tools-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"DOCUMENT_SEARCH_FAILED",FundToolSupport.safeMessage(ex));}}
    
    /** 执行该 Agent 运行时组件中的 evidence 操作。 */
    private EvidenceReference evidence(DocumentChunk c){String id="DOC:"+c.documentId()+":"+c.versionId()+":"+c.chunkId();String code=c.fundCodes().stream().sorted().findFirst().orElse(null);return EvidenceReference.document(id,code,c.sourceName(),c.documentId(),c.versionId(),c.chunkId(),c.documentTitle(),c.publishedDate(),c.pageStart(),c.pageEnd(),c.headingPath(),excerpt(c.content()),c.sourceUri());}
    
    /** 执行该 Agent 运行时组件中的 excerpt 操作。 */
    private String excerpt(String value){String cleaned=value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]"," ").trim();return cleaned.substring(0,Math.min(1200,cleaned.length()));}
    
    /** 在 Agent 运行时边界间传递 SearchInput 数据的不可变值对象。 */
    public record SearchInput(@NotEmpty @Size(max=10) List<@Pattern(regexp="\\d{6}") String> fundCodes,@NotBlank @Size(max=500) String query,Set<FundDocumentType> documentTypes,LocalDate publishedAfter,LocalDate publishedBefore,@Min(1) @Max(10) Integer topK){}
    
    /** 在 Agent 运行时边界间传递 DocumentSearchToolResult 数据的不可变值对象。 */
    public record DocumentSearchToolResult(String retrievalId,List<DocumentMatch>matches){}
    
    /** 在 Agent 运行时边界间传递 DocumentMatch 数据的不可变值对象。 */
    public record DocumentMatch(String documentTitle,FundDocumentType documentType,LocalDate publishedDate,int pageStart,int pageEnd,String headingPath,String excerpt,String evidenceId,Set<String>retrievalChannels){}
}
