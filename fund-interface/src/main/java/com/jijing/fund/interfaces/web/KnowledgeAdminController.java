package com.jijing.fund.interfaces.web;

import com.jijing.fund.interfaces.api.ApiResponse;
import com.jijing.fund.knowledge.api.DocumentRegistrationResult;
import com.jijing.fund.knowledge.api.IndexRebuildView;
import com.jijing.fund.knowledge.api.KnowledgeAdministrationUseCase;
import com.jijing.fund.knowledge.api.KnowledgeDocumentView;
import com.jijing.fund.knowledge.api.KnowledgeIndexGovernanceUseCase;
import com.jijing.fund.knowledge.api.KnowledgeIngestionUseCase;
import com.jijing.fund.knowledge.api.KnowledgeJobView;
import com.jijing.fund.knowledge.api.KnowledgeSearchQuery;
import com.jijing.fund.knowledge.api.KnowledgeSearchResult;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.api.KnowledgeVersionView;
import com.jijing.fund.knowledge.api.RegisterDocumentCommand;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.exception.KnowledgeInvalidArgumentException;
import com.jijing.fund.knowledge.exception.KnowledgeUnavailableException;
import com.jijing.fund.knowledge.port.AuthorizedDocumentProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库管理接口，仅在 {@code fund.knowledge.enabled=true} 时注册。
 * 路径位于 {@code /internal/**}，未认证返回 401，缺少分析师或管理员角色返回 403。
 * 文档、版本、任务或重建不存在返回 404，状态冲突返回 409，参数不合法返回 400。
 * 检索、导入或索引治理依赖缺失时返回 503。上传时的未知异常也会被转成 503。
 */
@RestController
@RequestMapping("/internal/v1/knowledge")
@ConditionalOnProperty(prefix = "fund.knowledge", name = "enabled", havingValue = "true")
public class KnowledgeAdminController {
    private final KnowledgeIngestionUseCase ingestion;
    private final KnowledgeSearchUseCase search;
    private final KnowledgeAdministrationUseCase administration;
    private final ObjectProvider<KnowledgeIndexGovernanceUseCase> governance;
    private final ObjectProvider<AuthorizedDocumentProvider> authorizedProvider;

    /**
     * 绑定入库、检索、查询，以及可选的索引治理和授权下载器。后两者缺失时对应路由返回 503。
     */
    public KnowledgeAdminController(KnowledgeIngestionUseCase ingestion, KnowledgeSearchUseCase search,
            KnowledgeAdministrationUseCase administration, ObjectProvider<KnowledgeIndexGovernanceUseCase> governance,
            ObjectProvider<AuthorizedDocumentProvider> authorizedProvider) {
        this.ingestion = ingestion;
        this.search = search;
        this.administration = administration;
        this.governance = governance;
        this.authorizedProvider = authorizedProvider;
    }

    /**
     * 接收 PDF、HTML 或纯文本。空文件、超过 50MB 或类型不匹配返回 400。
     * 重复内容返回 200，新内容返回 202。读取或入库中的其它异常返回 503。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentRegistrationResult>> upload(@RequestParam String title,
            @RequestParam FundDocumentType documentType,
            @RequestParam(defaultValue = "manual-upload") String sourceName,
            @RequestParam(required = false) String externalDocumentId,
            @RequestParam(required = false) String publisher,
            @RequestParam(required = false) String sourceUri,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate publishedDate,
            @RequestParam(defaultValue = "") List<String> fundCodes,
            @RequestPart("file") MultipartFile file, HttpServletRequest request) {
        try {
            if (file.isEmpty() || file.getSize() > 50L * 1024 * 1024) {
                throw new KnowledgeInvalidArgumentException("file size must be between 1 byte and 50 MB");
            }
            String contentType = file.getContentType();
            if (!Set.of("application/pdf", "text/html", "text/plain").contains(contentType)) {
                throw new KnowledgeInvalidArgumentException("Only PDF, HTML and plain text are supported");
            }
            URI uri = sourceUri == null || sourceUri.isBlank() ? null : URI.create(sourceUri);
            var command = new RegisterDocumentCommand(externalDocumentId, title, documentType, publisher, sourceName, uri,
                    publishedDate, new HashSet<>(fundCodes), file.getOriginalFilename(), contentType, file.getBytes());
            DocumentRegistrationResult result = ingestion.ingest(command);
            return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(RequestIdFilter.get(request), result));
        } catch (KnowledgeInvalidArgumentException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeInvalidArgumentException(ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new KnowledgeUnavailableException("Document upload registration failed", ex);
        }
    }

    /**
     * 按文档标识读取元数据。不存在返回 404。
     */
    @GetMapping("/documents/{documentId}")
    public ApiResponse<KnowledgeDocumentView> document(@PathVariable String documentId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), administration.document(documentId));
    }

    /**
     * 按版本标识读取一个文档版本。不存在返回 404。
     */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<KnowledgeVersionView> version(@PathVariable String versionId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), administration.version(versionId));
    }

    /**
     * 读取入库任务。不存在返回 404。
     */
    @GetMapping("/ingestion-jobs/{jobId}")
    public ApiResponse<KnowledgeJobView> job(@PathVariable String jobId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), administration.job(jobId));
    }

    /**
     * 重试失败的入库任务。任务不存在返回 404，状态不允许重试返回 409。
     */
    @PostMapping("/ingestion-jobs/{jobId}/retry")
    public ApiResponse<KnowledgeJobView> retry(@PathVariable String jobId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), administration.retry(jobId));
    }

    /**
     * 从已配置的授权地址拉取文档再入库。提供者未启用返回 503。
     * 地址或元数据不合法返回 400；拉取或入库中的运行时失败返回 503。重复内容返回 200，新内容返回 202。
     */
    @PostMapping("/documents/import")
    public ResponseEntity<ApiResponse<DocumentRegistrationResult>> importDocument(@Valid @RequestBody ImportRequest body,
            HttpServletRequest request) {
        AuthorizedDocumentProvider provider = authorizedProvider.getIfAvailable();
        if (provider == null) {
            throw new KnowledgeUnavailableException("Authorized document provider is disabled", null);
        }
        try {
            var fetched = provider.fetch(body.sourceUri());
            var command = new RegisterDocumentCommand(body.externalDocumentId(), body.title(), body.documentType(),
                    body.publisher(), body.sourceName(), fetched.sourceUri(), body.publishedDate(), body.fundCodes(),
                    fetched.fileName(), fetched.contentType(), fetched.content());
            var result = ingestion.ingest(command);
            return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(ApiResponse.success(RequestIdFilter.get(request), result));
        } catch (IllegalArgumentException e) {
            throw new KnowledgeInvalidArgumentException(e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new KnowledgeUnavailableException("Authorized document import failed", e);
        }
    }

    /**
     * 创建一次索引重建。治理组件未装配时返回 503。
     */
    @PostMapping("/index-rebuilds")
    public ApiResponse<IndexRebuildView> rebuild(HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), governance().create());
    }

    /**
     * 查询索引重建进度。标识不存在返回 404，治理组件缺失返回 503。
     */
    @GetMapping("/index-rebuilds/{rebuildId}")
    public ApiResponse<IndexRebuildView> rebuildStatus(@PathVariable String rebuildId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), governance().get(rebuildId));
    }

    /**
     * 把重建出的索引切为当前别名。状态冲突返回 409，治理组件缺失返回 503。
     */
    @PostMapping("/index-rebuilds/{rebuildId}/activate")
    public ApiResponse<IndexRebuildView> activate(@PathVariable String rebuildId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), governance().activate(rebuildId));
    }

    /**
     * 把别名切回重建前的索引。状态冲突返回 409，治理组件缺失返回 503。
     */
    @PostMapping("/index-rebuilds/{rebuildId}/rollback")
    public ApiResponse<IndexRebuildView> rollback(@PathVariable String rebuildId, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), governance().rollback(rebuildId));
    }

    /**
     * 取出可选的索引治理用例。未装配时明确返回 503，而不是空指针。
     */
    private KnowledgeIndexGovernanceUseCase governance() {
        KnowledgeIndexGovernanceUseCase value = governance.getIfAvailable();
        if (value == null) {
            throw new KnowledgeUnavailableException(
                    "Index governance requires fund.knowledge.index-type=elasticsearch", null);
        }
        return value;
    }

    /**
     * 调试检索。查询空白返回 400。参数不合法返回 400；检索运行时失败返回 503。
     * 知识库参数异常如果以运行时异常抛出，会被包成 503。
     */
    @PostMapping("/search/debug")
    public ApiResponse<KnowledgeSearchResult> search(@Valid @RequestBody SearchRequest body, HttpServletRequest request) {
        try {
            return ApiResponse.success(RequestIdFilter.get(request), search.search(new KnowledgeSearchQuery(
                    body.query(), body.fundCodes(), body.documentTypes(), body.publishedAfter(), body.publishedBefore(),
                    body.topK() == null ? 6 : body.topK())));
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeInvalidArgumentException(ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            throw new KnowledgeUnavailableException("Knowledge search is temporarily unavailable", ex);
        }
    }

    /**
     * 调试检索条件。topK 省略时按 6 条执行，显式值必须在 1 到 10 之间。
     */
    public record SearchRequest(@NotBlank @Size(max = 500) String query, Set<String> fundCodes,
            Set<FundDocumentType> documentTypes, LocalDate publishedAfter, LocalDate publishedBefore,
            @Min(1) @Max(10) Integer topK) {}

    /**
     * 授权导入请求。基金代码缺省时保存为空集合，避免下游遇到 null。
     */
    public record ImportRequest(@NotBlank String title, @NotNull FundDocumentType documentType,
            @NotBlank String sourceName, String externalDocumentId, String publisher, @NotNull URI sourceUri,
            LocalDate publishedDate, Set<String> fundCodes) {
        /**
         * 把缺省的基金代码收成不可变空集合。
         */
        public ImportRequest {
            fundCodes = fundCodes == null ? Set.of() : Set.copyOf(fundCodes);
        }
    }
}
