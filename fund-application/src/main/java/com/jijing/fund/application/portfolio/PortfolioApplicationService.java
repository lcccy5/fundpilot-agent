package com.jijing.fund.application.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.ImportBatch;
import com.jijing.fund.domain.portfolio.ImportBatchStatus;
import com.jijing.fund.domain.portfolio.ImportRow;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.domain.portfolio.PortfolioStatus;
import com.jijing.fund.domain.portfolio.TransactionType;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户名下的组合账本：登记流水、冲正、估值、收益、集中度，以及表格导入的预览和提交。
 * 组合不属于当前用户时抛出 {@link PortfolioNotFoundException}；业务规则不满足时抛出 {@link PortfolioException}，
 * 版本或导入状态冲突时抛出 {@link PortfolioConflictException}。操作者或组合标识为空会在读取时抛出空指针异常。
 */
public class PortfolioApplicationService implements PortfolioUseCase {
    private static final int MAX_IMPORT_BYTES = 5 * 1024 * 1024;
    private static final int MAX_IMPORT_ROWS = 2000;

    private final PortfolioRepository portfolios;
    private final FundNavRepository navs;
    private final PortfolioPositionProjector projector;
    private final MoneyWeightedReturnCalculator xirr;
    private final TimeWeightedReturnCalculator twr;
    private final PortfolioConcentrationCalculator concentration;
    private final List<SpreadsheetTableReader> readers;
    private final ObjectMapper json;
    private final Clock clock;

    /**
     * 组装账本用例。读取器列表为空引用时改用 CSV 读取器，JSON 映射器为空引用时新建默认映射器；
     * 时钟为空会留到下一次读时间时才抛出空指针异常。读取器列表中若含空元素，复制时抛出空指针异常。
     * 仓库、投影器和收益计算器为空则留到对应方法被调用时失败。
     */
    public PortfolioApplicationService(PortfolioRepository portfolios, FundNavRepository navs, PortfolioPositionProjector projector,
            MoneyWeightedReturnCalculator xirr, TimeWeightedReturnCalculator twr, PortfolioConcentrationCalculator concentration,
            List<SpreadsheetTableReader> readers, ObjectMapper json, Clock clock) {
        this.portfolios = portfolios;
        this.navs = navs;
        this.projector = projector;
        this.xirr = xirr;
        this.twr = twr;
        this.concentration = concentration;
        this.readers = readers == null ? List.of(new CsvSpreadsheetTableReader()) : List.copyOf(readers);
        this.json = json == null ? new ObjectMapper() : json;
        this.clock = clock;
    }

    /**
     * 返回该用户的全部组合，顺序由仓库决定。用户为空时抛出空指针异常。
     */
    @Override
    public List<UserPortfolio> list(AuthenticatedUser actor) {
        return portfolios.findByOwner(actor.userId());
    }

    /**
     * 以人民币、版本 0、活跃状态新建组合。名称为空、全空白或去空白后超过 80 个字符时抛出组合异常，不会落库。
     */
    @Override
    @Transactional
    public UserPortfolio create(AuthenticatedUser actor, String name) {
        String display = required(name);
        Instant now = clock.instant();
        var portfolio = new UserPortfolio(PortfolioId.random(), actor.userId(), display, "CNY", PortfolioStatus.ACTIVE, 0, now, now);
        portfolios.savePortfolio(portfolio);
        return portfolio;
    }

    /**
     * 追加一笔手工流水并重算持仓快照。组合不存在或不属于该用户时抛出找不到异常；已归档时抛出组合异常，
     * 即使幂等键已经存在也会先被归档检查拒绝。命令缺类型、日期或幂等键、确认日早于交易日、冲正缺少原流水号时抛出组合异常。
     * 基金代码不是六位数字时由代码值对象抛出参数异常。同一用户、组合和幂等键已有流水时直接返回旧流水，不再记账。
     */
    @Override
    @Transactional
    public FundTransaction append(AuthenticatedUser actor, PortfolioId id, TransactionCommand c) {
        var portfolio = owned(actor, id);
        if (portfolio.status() != PortfolioStatus.ACTIVE) {
            throw new PortfolioException("portfolio is archived");
        }
        validate(c);
        var duplicate = portfolios.findByIdempotency(actor.userId(), id, c.idempotencyKey());
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        var transaction = new FundTransaction(UUID.randomUUID().toString(), id, actor.userId(), new FundCode(c.fundCode()),
                c.type(), c.tradeDate(), c.confirmDate(), c.shares(), c.grossAmount(), c.fee(),
                c.confirmedNav(), "CNY", "MANUAL", c.idempotencyKey(), c.reversesTransactionId(), clock.instant());
        persist(actor, portfolio, List.of(transaction));
        return transaction;
    }

    /**
     * 为原流水写一笔方向相反的补偿交易，幂等键重复时直接返回已存在的补偿。幂等键为空或全空白时抛出组合异常。
     * 原流水不存在时抛出找不到异常；原流水本身已是冲正类型时抛出组合异常；已经有补偿时抛出冲突异常。
     * 不检查组合是否归档。幂等命中发生在归属检查之前，因此重复提交不会再次核对原流水。
     */
    @Override
    @Transactional
    public FundTransaction reverse(AuthenticatedUser actor, PortfolioId id, String transactionId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PortfolioException("idempotencyKey is required");
        }
        var duplicate = portfolios.findByIdempotency(actor.userId(), id, idempotencyKey);
        if (duplicate.isPresent()) {
            return duplicate.get();
        }
        var original = portfolios.findTransaction(actor.userId(), id, transactionId)
                .orElseThrow(() -> new PortfolioNotFoundException("transaction not found"));
        if (original.transactionType() == TransactionType.REVERSAL) {
            throw new PortfolioException("cannot reverse a reversal");
        }
        if (portfolios.hasReversal(actor.userId(), original.transactionId())) {
            throw new PortfolioConflictException("transaction already reversed");
        }
        var compensating = compensatingType(original.transactionType());
        var transaction = new FundTransaction(UUID.randomUUID().toString(), id, actor.userId(), original.fundCode(), compensating,
                original.tradeDate(), original.confirmDate(), original.shares(), original.grossAmount(), original.fee(),
                original.confirmedNav(), "CNY", "REVERSAL", idempotencyKey, original.transactionId(), clock.instant());
        persist(actor, owned(actor, id), List.of(transaction));
        return transaction;
    }

    /**
     * 返回组合内的全部流水。组合不存在或不属于该用户时抛出找不到异常。
     */
    @Override
    public List<FundTransaction> transactions(AuthenticatedUser actor, PortfolioId id) {
        owned(actor, id);
        return portfolios.findTransactions(id, actor.userId());
    }

    /**
     * 把流水投影成当前持仓。组合不存在时抛出找不到异常。流水里若直接出现冲正类型，投影器抛出参数异常。
     */
    @Override
    public List<FundPosition> positions(AuthenticatedUser actor, PortfolioId id) {
        owned(actor, id);
        return projector.project(portfolios.findTransactions(id, actor.userId()));
    }

    /**
     * 删除旧快照后按当前流水重建，并返回输入摘要与快照条数。组合不存在时抛出找不到异常。
     * 摘要来自持仓列表的文本形式，与记账时用流水号计算的摘要不是同一种算法。
     */
    @Override
    @Transactional
    public SnapshotRebuildResult rebuildSnapshots(AuthenticatedUser actor, PortfolioId id) {
        owned(actor, id);
        portfolios.deletePositionSnapshots(id, actor.userId());
        var positions = projector.project(portfolios.findTransactions(id, actor.userId()));
        String hash = sha(positions.toString());
        portfolios.replacePositionSnapshots(id, actor.userId(), LocalDate.now(clock), positions, hash);
        return new SnapshotRebuildResult(id, hash, positions.size(), portfolios.countPositionSnapshots(id, actor.userId()));
    }

    /**
     * 用不晚于基准日的最近单位净值估算市值。基准日为空时用时钟的当天。组合不存在时抛出找不到异常。
     * 任一持仓没有净值时，合计市值和浮盈都为空，并附上 NAV_UNAVAILABLE 警告；已估出的持仓不会填进合计。
     * 净值日等于基准日记为 AVAILABLE，更早则记为 STALE_NAV。
     */
    @Override
    public PortfolioValuation valuation(AuthenticatedUser actor, PortfolioId id, LocalDate asOf) {
        owned(actor, id);
        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        var values = new ArrayList<PortfolioValuation.PositionValue>();
        var warnings = new ArrayList<String>();
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal value = BigDecimal.ZERO;
        boolean incomplete = false;
        for (FundPosition position : positions(actor, id)) {
            cost = cost.add(position.remainingCost());
            var nav = navs.findHistory(position.fundCode(), LocalDate.of(1990, 1, 1), date).stream()
                    .filter(point -> !point.navDate().isAfter(date))
                    .max(Comparator.comparing(NavPoint::navDate));
            if (nav.isEmpty()) {
                incomplete = true;
                warnings.add("NAV_UNAVAILABLE:" + position.fundCode().value());
                values.add(new PortfolioValuation.PositionValue(position, null, null, null, "UNAVAILABLE"));
                continue;
            }
            BigDecimal marketValue = position.confirmedShares().multiply(nav.get().unitNav()).setScale(4, RoundingMode.HALF_UP);
            value = value.add(marketValue);
            String coverage = nav.get().navDate().equals(date) ? "AVAILABLE" : "STALE_NAV";
            values.add(new PortfolioValuation.PositionValue(position, nav.get().unitNav(), nav.get().navDate(), marketValue, coverage));
        }
        return new PortfolioValuation(id, date, cost, incomplete ? null : value, incomplete ? null : value.subtract(cost),
                List.copyOf(values), List.copyOf(warnings), "portfolio-position-v1");
    }

    /**
     * 在估值成功时计算资金加权和时间加权收益。基准日为空时用当天。组合不存在或净值不全时返回 DATA_NOT_READY，收益为空。
     * 资金加权无解时状态为 UNAVAILABLE，并追加 XIRR_UNAVAILABLE；时间加权无解只追加 TWR_UNAVAILABLE，不单独决定状态。
     * 流水中的冲正类型会在估值投影阶段抛出参数异常，而不是变成未就绪。
     */
    @Override
    public PortfolioReturn returns(AuthenticatedUser actor, PortfolioId id, LocalDate asOf) {
        LocalDate date = asOf == null ? LocalDate.now(clock) : asOf;
        var valuation = valuation(actor, id, date);
        var warnings = new ArrayList<>(valuation.warnings());
        if (valuation.totalValue() == null) {
            return new PortfolioReturn(id, date, null, null, "DATA_NOT_READY", List.of(), List.copyOf(warnings), "portfolio-return-v1");
        }
        var txs = portfolios.findTransactions(id, actor.userId()).stream()
                .filter(transaction -> !transaction.confirmDate().isAfter(date))
                .toList();
        var flows = new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
        for (var transaction : txs) {
            BigDecimal amount = cashFlowAmount(transaction);
            if (amount.signum() != 0) {
                flows.add(new MoneyWeightedReturnCalculator.CashFlow(transaction.confirmDate(), amount));
            }
        }
        if (valuation.totalValue().signum() != 0) {
            flows.add(new MoneyWeightedReturnCalculator.CashFlow(date, valuation.totalValue()));
        }
        var money = xirr.calculate(flows);
        if (money.isEmpty()) {
            warnings.add("XIRR_UNAVAILABLE");
        }
        var time = twr.calculate(subPeriods(valuation, txs, date));
        if (time.isEmpty()) {
            warnings.add("TWR_UNAVAILABLE");
        }
        String status = money.isPresent() ? "AVAILABLE" : "UNAVAILABLE";
        return new PortfolioReturn(id, date, money.orElse(null), time.orElse(null), status, List.copyOf(flows), List.copyOf(warnings),
                "portfolio-return-v1");
    }

    /**
     * 用各持仓市值计算集中度。组合不存在时抛出找不到异常。市值为空或非正数的持仓不参与权重；
     * 没有任何正市值时集中度状态为 UNAVAILABLE。覆盖度只看估值警告是否为空，与集中度状态相互独立。
     * 披露日和供应商标识固定为空。
     */
    @Override
    public PortfolioRiskView risk(AuthenticatedUser actor, PortfolioId id, LocalDate asOf) {
        var valuation = valuation(actor, id, asOf);
        var result = concentration.calculate(valuation.positions().stream().map(PortfolioValuation.PositionValue::value).toList());
        String coverage = valuation.warnings().isEmpty() ? "COMPLETE" : "PARTIAL";
        return new PortfolioRiskView(id, valuation.asOfDate(), result.maxFundWeight(), result.top3Weight(), result.hhi(),
                result.status(), coverage, valuation.warnings(), "portfolio-risk-v1", null, null);
    }

    /**
     * 解析表格并保存预览批次，不写交易流水。先确认组合归属，因此组合不存在时即使文件为空也抛出找不到异常。
     * 内容为空、长度为零或超过 5 MB 时抛出组合异常；文件名不是 .csv 或 .xlsx（忽略大小写）时抛出组合异常。
     * 没有读取器认领该文件，或数据行超过 2000 行时抛出组合异常。单行错误记入批次，不中断其余行。
     */
    @Override
    @Transactional
    public ImportBatch previewImport(AuthenticatedUser actor, PortfolioId id, String fileName, byte[] content) {
        owned(actor, id);
        if (content == null || content.length == 0 || content.length > MAX_IMPORT_BYTES) {
            throw new PortfolioException("import file must be 1 byte to 5 MB");
        }
        if (fileName == null || !(fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                || fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx"))) {
            throw new PortfolioException("only csv and xlsx are supported");
        }
        var table = reader(fileName, content).read(fileName, content);
        if (table.size() > MAX_IMPORT_ROWS) {
            throw new PortfolioException("import cannot exceed 2000 rows");
        }
        var rows = new ArrayList<ImportRow>();
        int valid = 0;
        for (int i = 0; i < table.size(); i++) {
            var parsed = parseRow(table.get(i), i + 2);
            if (parsed.errorCode() != null) {
                rows.add(parsed);
            } else {
                valid++;
                rows.add(parsed);
            }
        }
        Instant now = clock.instant();
        var batch = new ImportBatch(UUID.randomUUID().toString(), id, actor.userId(), sha(content), fileName,
                ImportBatchStatus.PREVIEWED, table.size(), valid, table.size() - valid, now, null, rows);
        portfolios.saveImportBatch(batch);
        return batch;
    }

    /**
     * 按用户和组合读取导入批次。批次不存在或不属于该用户时抛出找不到异常。
     */
    @Override
    public ImportBatch importBatch(AuthenticatedUser actor, PortfolioId id, String batchId) {
        return portfolios.findImportBatch(actor.userId(), id, batchId)
                .orElseThrow(() -> new PortfolioNotFoundException("import batch not found"));
    }

    /**
     * 把预览中的有效行写成导入流水并标记批次已提交。批次不存在时抛出找不到异常；状态不是已预览，
     * 或调用方给了非空哈希且与批次摘要不一致（忽略大小写）时抛出冲突异常。空哈希表示不核对文件。
     * 已存在的幂等键会被跳过。有效行在提交时无法还原成命令时抛出组合异常，批次保持未提交。
     * 全部无效时仍会推进组合版本并标记提交。
     */
    @Override
    @Transactional
    public ImportBatch commitImport(AuthenticatedUser actor, PortfolioId id, String batchId, String fileSha256) {
        var batch = importBatch(actor, id, batchId);
        if (batch.status() != ImportBatchStatus.PREVIEWED) {
            throw new PortfolioConflictException("import batch is not previewed");
        }
        if (fileSha256 != null && !fileSha256.isBlank() && !fileSha256.equalsIgnoreCase(batch.fileSha256())) {
            throw new PortfolioConflictException("file hash mismatch");
        }
        var portfolio = owned(actor, id);
        var txs = new ArrayList<FundTransaction>();
        for (ImportRow row : batch.rows()) {
            if (row.errorCode() != null) {
                continue;
            }
            var command = commandFromRow(row);
            var existing = portfolios.findByIdempotency(actor.userId(), id, command.idempotencyKey());
            if (existing.isEmpty()) {
                txs.add(new FundTransaction(UUID.randomUUID().toString(), id, actor.userId(), new FundCode(command.fundCode()),
                        command.type(), command.tradeDate(), command.confirmDate(), command.shares(), command.grossAmount(),
                        command.fee(), command.confirmedNav(), "CNY", "IMPORT", command.idempotencyKey(), null, clock.instant()));
            }
        }
        persist(actor, portfolio, txs);
        portfolios.markImportCommitted(batchId);
        return importBatch(actor, id, batchId);
    }

    /**
     * 删除尚未提交的导入批次。批次不存在时抛出找不到异常；已经提交时抛出冲突异常，批次仍保留。
     */
    @Override
    @Transactional
    public void deleteImport(AuthenticatedUser actor, PortfolioId id, String batchId) {
        var batch = importBatch(actor, id, batchId);
        if (batch.status() == ImportBatchStatus.COMMITTED) {
            throw new PortfolioConflictException("committed import cannot be deleted");
        }
        portfolios.deleteImportBatch(batchId);
    }

    /**
     * 先用包含新流水的候选序列做投影，再推进版本、追加流水并覆盖快照。投影失败时不会写库。
     * 版本已被他人推进时抛出冲突异常。快照摘要取候选流水号列表的文本形式。
     */
    private void persist(AuthenticatedUser actor, UserPortfolio portfolio, List<FundTransaction> txs) {
        List<FundTransaction> candidate = new ArrayList<>(portfolios.findTransactions(portfolio.portfolioId(), actor.userId()));
        candidate.addAll(txs);
        projector.project(candidate);
        if (!portfolios.updateVersion(portfolio.portfolioId(), actor.userId(), portfolio.version(), portfolio.version() + 1)) {
            throw new PortfolioConflictException("portfolio was updated concurrently");
        }
        txs.forEach(portfolios::appendTransaction);
        String hash = sha(candidate.stream().map(FundTransaction::transactionId).toList().toString());
        portfolios.replacePositionSnapshots(portfolio.portfolioId(), actor.userId(), LocalDate.now(clock), projector.project(candidate), hash);
    }

    /**
     * 读取当前用户拥有的组合。不存在或不属于该用户时抛出找不到异常；用户为空时抛出空指针异常。
     */
    private UserPortfolio owned(AuthenticatedUser actor, PortfolioId id) {
        return portfolios.findByIdAndOwner(id, actor.userId())
                .orElseThrow(() -> new PortfolioNotFoundException("portfolio not found"));
    }

    /**
     * 选择第一个声明支持该文件的读取器。都拒绝时抛出组合异常。文件名或内容的具体限制由读取器自己决定。
     */
    private SpreadsheetTableReader reader(String fileName, byte[] content) {
        return readers.stream()
                .filter(candidate -> candidate.supports(fileName, content))
                .findFirst()
                .orElseThrow(() -> new PortfolioException("unsupported import file"));
    }

    /**
     * 检查基金代码、交易类型和日期，成功时只保存原始行的 JSON。代码不是六位数字、类型无法识别或确认日早于交易日时返回带错误码的行；
     * 日期缺失、无法解析或其他异常都记为 INVALID_ROW。金额和份额留到提交时再解析，这里看起来有效并不表示能够入账。
     * 行号按表中的序号加 2，空行若已被读取器丢掉，行号会和源文件行号错开。
     */
    private ImportRow parseRow(Map<String, String> raw, int sourceRow) {
        try {
            String type = first(raw, "交易类型", "transactionType");
            String fund = first(raw, "基金代码", "fundCode");
            String trade = first(raw, "交易日期", "tradeDate");
            String confirm = first(raw, "确认日期", "confirmDate");
            if (fund == null || !fund.matches("\\d{6}")) {
                return err(sourceRow, raw, "INVALID_FUND_CODE", "fund code must be 6 digits");
            }
            TransactionType parsed = parseType(type);
            if (parsed == null) {
                return err(sourceRow, raw, "INVALID_TYPE", "unknown transaction type");
            }
            LocalDate tradeDate = LocalDate.parse(trade);
            LocalDate confirmDate = LocalDate.parse(confirm);
            if (confirmDate.isBefore(tradeDate)) {
                return err(sourceRow, raw, "INVALID_DATE", "confirmDate cannot precede tradeDate");
            }
            return new ImportRow(sourceRow, json.writeValueAsString(raw), null, null);
        } catch (Exception exception) {
            return err(sourceRow, raw, "INVALID_ROW", "row cannot be parsed");
        }
    }

    /**
     * 把预览时保存的原始 JSON 还原成记账命令。外部流水号为空时，幂等键由行号和原文摘要前 12 位组成。
     * JSON 损坏、金额无法解析或类型丢失时抛出组合异常。冲正引用固定为空，因此导入冲正行会在后续投影时失败。
     */
    private TransactionCommand commandFromRow(ImportRow row) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> raw = json.readValue(row.rawJson(), Map.class);
            String external = first(raw, "外部流水号", "externalReference");
            String idempotency = external == null || external.isBlank()
                    ? "import-" + row.sourceRowNumber() + "-" + sha(row.rawJson()).substring(0, 12)
                    : external;
            return new TransactionCommand(first(raw, "基金代码", "fundCode"), parseType(first(raw, "交易类型", "transactionType")),
                    LocalDate.parse(first(raw, "交易日期", "tradeDate")), LocalDate.parse(first(raw, "确认日期", "confirmDate")),
                    decimal(first(raw, "确认份额", "shares")), decimal(first(raw, "交易金额", "grossAmount")),
                    decimal(first(raw, "手续费", "fee")), decimal(first(raw, "确认净值", "confirmedNav")),
                    idempotency, first(raw, "备注", "note"), null);
        } catch (Exception exception) {
            throw new PortfolioException("committed row is invalid");
        }
    }

    /**
     * 用第一笔未晚于基准日的流水到估值日构成单一时间加权区间。估值为空或没有流水时返回空列表，调用方据此把时间加权标为不可用。
     * 区间不按中途现金流拆分，流水顺序沿用仓库返回的顺序。
     */
    private List<TimeWeightedReturnCalculator.SubPeriod> subPeriods(PortfolioValuation valuation, List<FundTransaction> txs, LocalDate date) {
        if (valuation.totalValue() == null || txs.isEmpty()) {
            return List.of();
        }
        return List.of(new TimeWeightedReturnCalculator.SubPeriod(txs.getFirst().confirmDate(), date, valuation.totalCost(),
                valuation.totalValue(), BigDecimal.ZERO));
    }

    /**
     * 把流水换成资金加权使用的有符号现金流。申购和转换转入为资金流出，赎回和转换转出为流入，现金分红为正，手续费调整为负。
     * 红利再投资和冲正类型贡献零，调用方会丢掉零金额。未知类型无法编译通过；金额为空时由流水值对象先变成零。
     */
    private static BigDecimal cashFlowAmount(FundTransaction transaction) {
        return switch (transaction.transactionType()) {
            case SUBSCRIPTION, CONVERSION_IN -> transaction.grossAmount().add(transaction.fee()).negate();
            case REDEMPTION, CONVERSION_OUT -> transaction.grossAmount().subtract(transaction.fee());
            case CASH_DIVIDEND -> transaction.grossAmount();
            case FEE_ADJUSTMENT -> transaction.fee().negate();
            case DIVIDEND_REINVESTMENT, REVERSAL -> BigDecimal.ZERO;
        };
    }

    /**
     * 选择冲正时写入的补偿类型。申购、再投资和转换转入补偿为赎回，赎回和转换转出补偿为申购，
     * 现金分红和手续费调整补偿为手续费调整。冲正类型会映射成冲正，但调用方在此之前已经拒绝对冲正再冲正。
     */
    private static TransactionType compensatingType(TransactionType type) {
        return switch (type) {
            case SUBSCRIPTION, DIVIDEND_REINVESTMENT, CONVERSION_IN -> TransactionType.REDEMPTION;
            case REDEMPTION, CONVERSION_OUT -> TransactionType.SUBSCRIPTION;
            case CASH_DIVIDEND, FEE_ADJUSTMENT -> TransactionType.FEE_ADJUSTMENT;
            case REVERSAL -> TransactionType.REVERSAL;
        };
    }

    /**
     * 把中文或英文类型文本解析成枚举。空文本返回空。先匹配常用中文名和枚举名，再尝试按枚举名解析；都失败时返回空，不抛异常。
     * 比较前会去掉首尾空白并转成大写，因此小写英文可以识别，全角或带空格的中文不能。
     */
    private static TransactionType parseType(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "申购", "SUBSCRIPTION" -> TransactionType.SUBSCRIPTION;
            case "赎回", "REDEMPTION" -> TransactionType.REDEMPTION;
            case "现金分红", "CASH_DIVIDEND" -> TransactionType.CASH_DIVIDEND;
            case "红利再投资", "DIVIDEND_REINVESTMENT" -> TransactionType.DIVIDEND_REINVESTMENT;
            case "转换转出", "CONVERSION_OUT" -> TransactionType.CONVERSION_OUT;
            case "转换转入", "CONVERSION_IN" -> TransactionType.CONVERSION_IN;
            case "冲正", "REVERSAL" -> TransactionType.REVERSAL;
            case "手续费调整", "FEE_ADJUSTMENT" -> TransactionType.FEE_ADJUSTMENT;
            default -> {
                try {
                    yield TransactionType.valueOf(normalized);
                } catch (Exception exception) {
                    yield null;
                }
            }
        };
    }

    /**
     * 生成带错误码的导入行，并尽量保留原始单元格。原始内容无法序列化时改存空对象文本，仍然返回该错误码。
     */
    private ImportRow err(int row, Map<String, String> raw, String code, String message) {
        try {
            return new ImportRow(row, json.writeValueAsString(raw), code, message);
        } catch (Exception exception) {
            return new ImportRow(row, "{}", code, message);
        }
    }

    /**
     * 按给定列名顺序找第一个非空白单元格，列名比较忽略大小写和首尾空白。都没有时返回空。
     * 行映射为空时抛出空指针异常；单元格值会去掉首尾空白。
     */
    private static String first(Map<String, String> raw, String... keys) {
        for (String key : keys) {
            for (var entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(key)
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    return entry.getValue().trim();
                }
            }
        }
        return null;
    }

    /**
     * 把单元格转成小数。空文本视为零。无法解析时抛出数字格式异常，由调用方转换成行错误或提交失败。
     */
    private static BigDecimal decimal(String value) {
        return value == null || value.isBlank() ? BigDecimal.ZERO : new BigDecimal(value);
    }

    /**
     * 检查手工记账命令的必填项。命令、类型、两个日期或幂等键缺失时抛出组合异常；确认日早于交易日，
     * 或冲正没有原流水号时同样抛出组合异常。不检查基金代码、金额正负和份额。
     */
    private static void validate(TransactionCommand command) {
        if (command == null || command.type() == null || command.tradeDate() == null || command.confirmDate() == null
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new PortfolioException("transaction fields are required");
        }
        if (command.confirmDate().isBefore(command.tradeDate())) {
            throw new PortfolioException("confirmDate cannot precede tradeDate");
        }
        if (command.type() == TransactionType.REVERSAL
                && (command.reversesTransactionId() == null || command.reversesTransactionId().isBlank())) {
            throw new PortfolioException("reversal must reference original transaction");
        }
    }

    /**
     * 去掉组合名称的首尾空白。空、全空白或长于 80 个字符时抛出组合异常，三种情况使用同一句提示。
     */
    private static String required(String name) {
        if (name == null) {
            throw new PortfolioException("portfolio name is invalid");
        }
        String trimmed = name.trim();
        if (trimmed.isBlank() || trimmed.length() > 80) {
            throw new PortfolioException("portfolio name is invalid");
        }
        return trimmed;
    }

    /**
     * 计算 SHA-256 的十六进制摘要。内容为空时抛出空指针异常；算法不可用时包装成非法状态异常。
     */
    private static String sha(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 按 UTF-8 计算文本摘要。文本为空时抛出空指针异常。
     */
    private static String sha(String value) {
        return sha(value.getBytes(StandardCharsets.UTF_8));
    }
}
