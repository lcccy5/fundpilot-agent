package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.portfolio.*;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.*;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;import java.time.LocalDate;import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {
    private final PortfolioUseCase useCase;public PortfolioController(PortfolioUseCase useCase){this.useCase=useCase;}
    @GetMapping public ApiResponse<List<UserPortfolio>> list(@CurrentUser AuthenticatedUser actor,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.list(actor));}
    @PostMapping public ApiResponse<UserPortfolio> create(@CurrentUser AuthenticatedUser actor,@Valid @RequestBody PortfolioBody b,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.create(actor,b.name()));}
    @GetMapping("/{portfolioId}/transactions") public ApiResponse<List<FundTransaction>> transactions(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.transactions(actor,new PortfolioId(portfolioId)));}
    @PostMapping("/{portfolioId}/transactions") public ApiResponse<FundTransaction> append(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@Valid @RequestBody TransactionBody b,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.append(actor,new PortfolioId(portfolioId),new TransactionCommand(b.fundCode(),b.type(),b.tradeDate(),b.confirmDate(),b.shares(),b.grossAmount(),b.fee(),b.confirmedNav(),b.idempotencyKey(),b.note(),b.reversesTransactionId())));}
    @PostMapping("/{portfolioId}/transactions/{transactionId}/reverse") public ApiResponse<FundTransaction> reverse(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@PathVariable("transactionId") String transactionId,@Valid @RequestBody ReverseBody b,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.reverse(actor,new PortfolioId(portfolioId),transactionId,b.idempotencyKey()));}
    @GetMapping("/{portfolioId}/positions") public ApiResponse<List<FundPosition>> positions(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.positions(actor,new PortfolioId(portfolioId)));}
    @PostMapping("/{portfolioId}/snapshots/rebuild") public ApiResponse<SnapshotRebuildResult> rebuild(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.rebuildSnapshots(actor,new PortfolioId(portfolioId)));}
    @GetMapping("/{portfolioId}/valuation") public ApiResponse<PortfolioValuation> valuation(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@RequestParam(required=false) LocalDate asOfDate,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.valuation(actor,new PortfolioId(portfolioId),asOfDate));}
    @GetMapping("/{portfolioId}/returns") public ApiResponse<PortfolioReturn> returns(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@RequestParam(required=false) LocalDate asOfDate,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.returns(actor,new PortfolioId(portfolioId),asOfDate));}
    @GetMapping("/{portfolioId}/risk") public ApiResponse<PortfolioRiskView> risk(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@RequestParam(required=false) LocalDate asOfDate,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.risk(actor,new PortfolioId(portfolioId),asOfDate));}
    @PostMapping(value="/{portfolioId}/imports/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportBatch> preview(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@RequestPart("file") MultipartFile file,HttpServletRequest r)throws Exception{
        return ApiResponse.success(RequestIdFilter.get(r),useCase.previewImport(actor,new PortfolioId(portfolioId),file.getOriginalFilename(),file.getBytes()));
    }
    @GetMapping("/{portfolioId}/imports/{batchId}") public ApiResponse<ImportBatch> batch(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@PathVariable("batchId") String batchId,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.importBatch(actor,new PortfolioId(portfolioId),batchId));}
    @PostMapping("/{portfolioId}/imports/{batchId}/commit") public ApiResponse<ImportBatch> commit(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@PathVariable("batchId") String batchId,@RequestBody(required=false) CommitBody body,HttpServletRequest r){return ApiResponse.success(RequestIdFilter.get(r),useCase.commitImport(actor,new PortfolioId(portfolioId),batchId,body==null?null:body.fileSha256()));}
    @DeleteMapping("/{portfolioId}/imports/{batchId}") public ApiResponse<Void> deleteImport(@CurrentUser AuthenticatedUser actor,@PathVariable("portfolioId") String portfolioId,@PathVariable("batchId") String batchId,HttpServletRequest r){useCase.deleteImport(actor,new PortfolioId(portfolioId),batchId);return ApiResponse.success(RequestIdFilter.get(r),null);}
    public record PortfolioBody(@NotBlank @Size(max=80) String name){}
    public record TransactionBody(@Pattern(regexp="\\d{6}") String fundCode,@NotNull TransactionType type,@NotNull LocalDate tradeDate,@NotNull LocalDate confirmDate,@DecimalMin("0") BigDecimal shares,@DecimalMin("0") BigDecimal grossAmount,@DecimalMin("0") BigDecimal fee,@DecimalMin("0") BigDecimal confirmedNav,@NotBlank @Size(max=128) String idempotencyKey,@Size(max=500) String note,@Size(max=36) String reversesTransactionId){}
    public record ReverseBody(@NotBlank @Size(max=128) String idempotencyKey){}
    public record CommitBody(@Size(max=64) String fileSha256){}
}
