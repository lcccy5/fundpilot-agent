package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.portfolio.PortfolioReturn;
import com.jijing.fund.application.portfolio.PortfolioRiskView;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.portfolio.PortfolioValuation;
import com.jijing.fund.application.portfolio.SnapshotRebuildResult;
import com.jijing.fund.application.portfolio.TransactionCommand;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.ImportBatch;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.TransactionType;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 当前用户的组合、交易、估值与账单导入。
 * 匿名请求返回 401。组合或导入批次不属于当前用户时返回 404，并发写入冲突返回 409。
 * 名称、交易字段或日期无法解析时返回 400。估值所依赖的外部净值失败时返回 502 或 503，其它未分类异常返回 500。
 */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {
    private final PortfolioUseCase useCase;

    /**
     * 绑定按当前用户隔离的组合用例。
     */
    public PortfolioController(PortfolioUseCase useCase) {
        this.useCase = useCase;
    }

    /**
     * 列出当前用户的组合。未登录返回 401。
     */
    @GetMapping
    public ApiResponse<List<UserPortfolio>> list(@CurrentUser AuthenticatedUser actor, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.list(actor));
    }

    /**
     * 按名称创建组合。名称为空或超长返回 400，名称冲突返回 409。
     */
    @PostMapping
    public ApiResponse<UserPortfolio> create(@CurrentUser AuthenticatedUser actor, @Valid @RequestBody PortfolioBody b,
            HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.create(actor, b.name()));
    }

    /**
     * 列出组合内的交易。组合不存在或不属于当前用户返回 404。
     */
    @GetMapping("/{portfolioId}/transactions")
    public ApiResponse<List<FundTransaction>> transactions(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.transactions(actor, new PortfolioId(portfolioId)));
    }

    /**
     * 追加一笔交易。字段不合法返回 400，幂等键冲突返回 409，组合不存在返回 404。
     */
    @PostMapping("/{portfolioId}/transactions")
    public ApiResponse<FundTransaction> append(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @Valid @RequestBody TransactionBody b, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.append(actor, new PortfolioId(portfolioId),
                new TransactionCommand(b.fundCode(), b.type(), b.tradeDate(), b.confirmDate(), b.shares(),
                        b.grossAmount(), b.fee(), b.confirmedNav(), b.idempotencyKey(), b.note(), b.reversesTransactionId())));
    }

    /**
     * 冲正一笔已有交易。交易或组合不存在返回 404，重复冲正返回 409。
     */
    @PostMapping("/{portfolioId}/transactions/{transactionId}/reverse")
    public ApiResponse<FundTransaction> reverse(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @PathVariable("transactionId") String transactionId,
            @Valid @RequestBody ReverseBody b, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r),
                useCase.reverse(actor, new PortfolioId(portfolioId), transactionId, b.idempotencyKey()));
    }

    /**
     * 返回由交易投影出的持仓。组合不存在返回 404。
     */
    @GetMapping("/{portfolioId}/positions")
    public ApiResponse<List<FundPosition>> positions(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.positions(actor, new PortfolioId(portfolioId)));
    }

    /**
     * 按当前交易重算持仓快照。组合不存在返回 404。
     */
    @PostMapping("/{portfolioId}/snapshots/rebuild")
    public ApiResponse<SnapshotRebuildResult> rebuild(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.rebuildSnapshots(actor, new PortfolioId(portfolioId)));
    }

    /**
     * 按可选截止日期做估值。日期格式错误返回 400，组合不存在返回 404，净值上游失败返回 502 或 503。
     */
    @GetMapping("/{portfolioId}/valuation")
    public ApiResponse<PortfolioValuation> valuation(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @RequestParam(required = false) LocalDate asOfDate,
            HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.valuation(actor, new PortfolioId(portfolioId), asOfDate));
    }

    /**
     * 计算组合收益。日期格式错误返回 400，组合不存在返回 404。
     */
    @GetMapping("/{portfolioId}/returns")
    public ApiResponse<PortfolioReturn> returns(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @RequestParam(required = false) LocalDate asOfDate,
            HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.returns(actor, new PortfolioId(portfolioId), asOfDate));
    }

    /**
     * 返回组合集中度等风险视图。日期格式错误返回 400，组合不存在返回 404。
     */
    @GetMapping("/{portfolioId}/risk")
    public ApiResponse<PortfolioRiskView> risk(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @RequestParam(required = false) LocalDate asOfDate,
            HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.risk(actor, new PortfolioId(portfolioId), asOfDate));
    }

    /**
     * 预览账单文件，不落交易。文件无法读取时异常向外抛出并成为 500；组合不存在返回 404，内容不合法返回 400。
     */
    @PostMapping(value = "/{portfolioId}/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportBatch> preview(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @RequestPart("file") MultipartFile file,
            HttpServletRequest r) throws Exception {
        return ApiResponse.success(RequestIdFilter.get(r),
                useCase.previewImport(actor, new PortfolioId(portfolioId), file.getOriginalFilename(), file.getBytes()));
    }

    /**
     * 读取一次导入批次。批次或组合不属于当前用户时返回 404。
     */
    @GetMapping("/{portfolioId}/imports/{batchId}")
    public ApiResponse<ImportBatch> batch(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @PathVariable("batchId") String batchId,
            HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r), useCase.importBatch(actor, new PortfolioId(portfolioId), batchId));
    }

    /**
     * 提交预览过的导入批次。批次不存在返回 404，重复提交或校验失败返回 409 或 400。
     */
    @PostMapping("/{portfolioId}/imports/{batchId}/commit")
    public ApiResponse<ImportBatch> commit(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @PathVariable("batchId") String batchId,
            @RequestBody(required = false) CommitBody body, HttpServletRequest r) {
        return ApiResponse.success(RequestIdFilter.get(r),
                useCase.commitImport(actor, new PortfolioId(portfolioId), batchId, body == null ? null : body.fileSha256()));
    }

    /**
     * 删除尚未提交或允许撤回的导入批次。批次不存在返回 404。
     */
    @DeleteMapping("/{portfolioId}/imports/{batchId}")
    public ApiResponse<Void> deleteImport(@CurrentUser AuthenticatedUser actor,
            @PathVariable("portfolioId") String portfolioId, @PathVariable("batchId") String batchId,
            HttpServletRequest r) {
        useCase.deleteImport(actor, new PortfolioId(portfolioId), batchId);
        return ApiResponse.success(RequestIdFilter.get(r), null);
    }

    /**
     * 新建组合的名称，最长 80 个字符。
     */
    public record PortfolioBody(@NotBlank @Size(max = 80) String name) {}

    /**
     * 一笔基金交易。金额字段不得为负，幂等键必填。
     */
    public record TransactionBody(@Pattern(regexp = "\\d{6}") String fundCode, @NotNull TransactionType type,
            @NotNull LocalDate tradeDate, @NotNull LocalDate confirmDate, @DecimalMin("0") BigDecimal shares,
            @DecimalMin("0") BigDecimal grossAmount, @DecimalMin("0") BigDecimal fee,
            @DecimalMin("0") BigDecimal confirmedNav, @NotBlank @Size(max = 128) String idempotencyKey,
            @Size(max = 500) String note, @Size(max = 36) String reversesTransactionId) {}

    /**
     * 冲正时单独提供的幂等键，避免与原交易键冲突。
     */
    public record ReverseBody(@NotBlank @Size(max = 128) String idempotencyKey) {}

    /**
     * 提交导入时可选的文件摘要，用于确认预览和提交的是同一份文件。
     */
    public record CommitBody(@Size(max = 64) String fileSha256) {}
}
