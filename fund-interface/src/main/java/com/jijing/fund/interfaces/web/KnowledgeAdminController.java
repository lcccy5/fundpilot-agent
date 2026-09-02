package com.jijing.fund.interfaces.web;

import com.jijing.fund.interfaces.api.ApiResponse;
import com.jijing.fund.knowledge.api.*;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.exception.*;
import com.jijing.fund.knowledge.port.AuthorizedDocumentProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.ObjectProvider;

@RestController
@RequestMapping("/internal/v1/knowledge")
@ConditionalOnProperty(prefix="fund.knowledge",name="enabled",havingValue="true")
public class KnowledgeAdminController {
    private final KnowledgeIngestionUseCase ingestion;private final KnowledgeSearchUseCase search;private final KnowledgeAdministrationUseCase administration;private final ObjectProvider<KnowledgeIndexGovernanceUseCase>governance;private final ObjectProvider<AuthorizedDocumentProvider>authorizedProvider;
    public KnowledgeAdminController(KnowledgeIngestionUseCase ingestion,KnowledgeSearchUseCase search,KnowledgeAdministrationUseCase administration,ObjectProvider<KnowledgeIndexGovernanceUseCase>governance,ObjectProvider<AuthorizedDocumentProvider>authorizedProvider){this.ingestion=ingestion;this.search=search;this.administration=administration;this.governance=governance;this.authorizedProvider=authorizedProvider;}
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentRegistrationResult>> upload(@RequestParam String title,@RequestParam FundDocumentType documentType,
            @RequestParam(defaultValue="manual-upload") String sourceName,@RequestParam(required=false) String externalDocumentId,
            @RequestParam(required=false) String publisher,@RequestParam(required=false) String sourceUri,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate publishedDate,
            @RequestParam(defaultValue="") List<String> fundCodes,@RequestPart("file") MultipartFile file,HttpServletRequest request){
        try{if(file.isEmpty()||file.getSize()>50L*1024*1024)throw new KnowledgeInvalidArgumentException("file size must be between 1 byte and 50 MB");String contentType=file.getContentType();if(!Set.of("application/pdf","text/html","text/plain").contains(contentType))throw new KnowledgeInvalidArgumentException("Only PDF, HTML and plain text are supported");URI uri=sourceUri==null||sourceUri.isBlank()?null:URI.create(sourceUri);var command=new RegisterDocumentCommand(externalDocumentId,title,documentType,publisher,sourceName,uri,publishedDate,new HashSet<>(fundCodes),file.getOriginalFilename(),contentType,file.getBytes());DocumentRegistrationResult result=ingestion.ingest(command);return ResponseEntity.status(result.duplicate()?HttpStatus.OK:HttpStatus.ACCEPTED).body(ApiResponse.success(RequestIdFilter.get(request),result));}catch(KnowledgeInvalidArgumentException ex){throw ex;}catch(IllegalArgumentException ex){throw new KnowledgeInvalidArgumentException(ex.getMessage(),ex);}catch(Exception ex){throw new KnowledgeUnavailableException("Document upload registration failed",ex);}}
    @GetMapping("/documents/{documentId}") public ApiResponse<KnowledgeDocumentView> document(@PathVariable String documentId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),administration.document(documentId));}
    @GetMapping("/versions/{versionId}") public ApiResponse<KnowledgeVersionView> version(@PathVariable String versionId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),administration.version(versionId));}
    @GetMapping("/ingestion-jobs/{jobId}") public ApiResponse<KnowledgeJobView> job(@PathVariable String jobId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),administration.job(jobId));}
    @PostMapping("/ingestion-jobs/{jobId}/retry") public ApiResponse<KnowledgeJobView> retry(@PathVariable String jobId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),administration.retry(jobId));}
    @PostMapping("/documents/import") public ResponseEntity<ApiResponse<DocumentRegistrationResult>> importDocument(@Valid @RequestBody ImportRequest body,HttpServletRequest request){AuthorizedDocumentProvider provider=authorizedProvider.getIfAvailable();if(provider==null)throw new KnowledgeUnavailableException("Authorized document provider is disabled",null);try{var fetched=provider.fetch(body.sourceUri());var command=new RegisterDocumentCommand(body.externalDocumentId(),body.title(),body.documentType(),body.publisher(),body.sourceName(),fetched.sourceUri(),body.publishedDate(),body.fundCodes(),fetched.fileName(),fetched.contentType(),fetched.content());var result=ingestion.ingest(command);return ResponseEntity.status(result.duplicate()?HttpStatus.OK:HttpStatus.ACCEPTED).body(ApiResponse.success(RequestIdFilter.get(request),result));}catch(IllegalArgumentException e){throw new KnowledgeInvalidArgumentException(e.getMessage(),e);}catch(RuntimeException e){throw new KnowledgeUnavailableException("Authorized document import failed",e);}}
    @PostMapping("/index-rebuilds") public ApiResponse<IndexRebuildView> rebuild(HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),governance().create());}
    @GetMapping("/index-rebuilds/{rebuildId}") public ApiResponse<IndexRebuildView> rebuildStatus(@PathVariable String rebuildId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),governance().get(rebuildId));}
    @PostMapping("/index-rebuilds/{rebuildId}/activate") public ApiResponse<IndexRebuildView> activate(@PathVariable String rebuildId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),governance().activate(rebuildId));}
    @PostMapping("/index-rebuilds/{rebuildId}/rollback") public ApiResponse<IndexRebuildView> rollback(@PathVariable String rebuildId,HttpServletRequest request){return ApiResponse.success(RequestIdFilter.get(request),governance().rollback(rebuildId));}
    private KnowledgeIndexGovernanceUseCase governance(){KnowledgeIndexGovernanceUseCase value=governance.getIfAvailable();if(value==null)throw new KnowledgeUnavailableException("Index governance requires fund.knowledge.index-type=elasticsearch",null);return value;}
    @PostMapping("/search/debug")
    public ApiResponse<KnowledgeSearchResult> search(@Valid @RequestBody SearchRequest body,HttpServletRequest request){try{return ApiResponse.success(RequestIdFilter.get(request),search.search(new KnowledgeSearchQuery(body.query(),body.fundCodes(),body.documentTypes(),body.publishedAfter(),body.publishedBefore(),body.topK()==null?6:body.topK())));}catch(IllegalArgumentException ex){throw new KnowledgeInvalidArgumentException(ex.getMessage(),ex);}catch(RuntimeException ex){throw new KnowledgeUnavailableException("Knowledge search is temporarily unavailable",ex);}}
    public record SearchRequest(@NotBlank @Size(max=500)String query,Set<String>fundCodes,Set<FundDocumentType>documentTypes,LocalDate publishedAfter,LocalDate publishedBefore,@Min(1)@Max(10)Integer topK){}
    public record ImportRequest(@NotBlank String title,@NotNull FundDocumentType documentType,@NotBlank String sourceName,String externalDocumentId,String publisher,@NotNull URI sourceUri,LocalDate publishedDate,Set<String>fundCodes){public ImportRequest{fundCodes=fundCodes==null?Set.of():Set.copyOf(fundCodes);}}
}
