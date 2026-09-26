package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 组合、成交、导入批次和持仓快照。版本更新带期望版本，不符返回 false。
 * 幂等键查询用于识别重复提交，本类不在插入时忽略冲突，重复主键由数据库抛出。
 * 冲正是否已存在用计数判断。没有 HTTP 超时。
 */
public class JdbcPortfolioRepository implements PortfolioRepository {
    private final JdbcTemplate jdbc;

    /** 不在构造时访问表。 */
    public JdbcPortfolioRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按更新时间倒序。 */
    @Override
    public List<UserPortfolio> findByOwner(UserId owner) {
        return jdbc.query("SELECT * FROM user_portfolio WHERE owner_user_id=? ORDER BY updated_at DESC",
                this::portfolio, owner.value());
    }

    /** 编号存在但不属于该用户时为空。 */
    @Override
    public Optional<UserPortfolio> findByIdAndOwner(PortfolioId id, UserId owner) {
        return jdbc.query("SELECT * FROM user_portfolio WHERE portfolio_id=? AND owner_user_id=?",
                this::portfolio, id.value(), owner.value()).stream().findFirst();
    }

    /** 插入组合。版本由领域对象给出。 */
    @Override
    public void savePortfolio(UserPortfolio portfolio) {
        jdbc.update("""
                INSERT INTO user_portfolio(portfolio_id,owner_user_id,display_name,currency,status,version,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, portfolio.portfolioId().value(), portfolio.ownerUserId().value(), portfolio.displayName(),
                portfolio.currency(), portfolio.status().name(), portfolio.version(), ts(portfolio.createdAt()),
                ts(portfolio.updatedAt()));
    }

    /** 乐观锁。更新时间取调用时的系统时钟。 */
    @Override
    public boolean updateVersion(PortfolioId id, UserId owner, long expected, long next) {
        return jdbc.update("""
                UPDATE user_portfolio SET version=?,updated_at=?
                WHERE portfolio_id=? AND owner_user_id=? AND version=?
                """, next, ts(Instant.now()), id.value(), owner.value(), expected) > 0;
    }

    /** 同一所有者、组合和幂等键已有成交时返回它，供调用方跳过重复提交。 */
    @Override
    public Optional<FundTransaction> findByIdempotency(UserId owner, PortfolioId id, String key) {
        return jdbc.query(
                "SELECT * FROM fund_transaction WHERE owner_user_id=? AND portfolio_id=? AND idempotency_key=?",
                this::transaction, owner.value(), id.value(), key).stream().findFirst();
    }

    /** 按成交编号查找，必须同时匹配所有者和组合。 */
    @Override
    public Optional<FundTransaction> findTransaction(UserId owner, PortfolioId id, String tx) {
        return jdbc.query(
                "SELECT * FROM fund_transaction WHERE owner_user_id=? AND portfolio_id=? AND transaction_id=?",
                this::transaction, owner.value(), id.value(), tx).stream().findFirst();
    }

    /** 计数大于 0 表示原成交已经被冲正过。 */
    @Override
    public boolean hasReversal(UserId owner, String original) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fund_transaction WHERE owner_user_id=? AND reverses_transaction_id=?",
                Integer.class, owner.value(), original);
        return count != null && count > 0;
    }

    /** 追加成交。创建人列写所有者。重复主键或幂等键由数据库拒绝。 */
    @Override
    public void appendTransaction(FundTransaction transaction) {
        jdbc.update("""
                INSERT INTO fund_transaction(transaction_id,portfolio_id,owner_user_id,fund_code,transaction_type,trade_date,confirm_date,shares,gross_amount,fee,confirmed_nav,currency,source,idempotency_key,reverses_transaction_id,created_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, transaction.transactionId(), transaction.portfolioId().value(), transaction.ownerUserId().value(),
                transaction.fundCode().value(), transaction.transactionType().name(), transaction.tradeDate(),
                transaction.confirmDate(), transaction.shares(), transaction.grossAmount(), transaction.fee(),
                transaction.confirmedNav(), transaction.currency(), transaction.source(), transaction.idempotencyKey(),
                transaction.reversesTransactionId(), ts(transaction.createdAt()), transaction.ownerUserId().value());
    }

    /** 按确认日和成交编号排序，便于重放持仓。 */
    @Override
    public List<FundTransaction> findTransactions(PortfolioId id, UserId owner) {
        return jdbc.query("""
                SELECT * FROM fund_transaction WHERE portfolio_id=? AND owner_user_id=?
                ORDER BY confirm_date,transaction_id
                """, this::transaction, id.value(), owner.value());
    }

    /** 批次头和每一行一起写入。行上的错误码可以为空。 */
    @Override
    public void saveImportBatch(ImportBatch batch) {
        jdbc.update("""
                INSERT INTO portfolio_import_batch(batch_id,portfolio_id,owner_user_id,file_sha256,file_name,status,total_rows,valid_rows,invalid_rows,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, batch.batchId(), batch.portfolioId().value(), batch.ownerUserId().value(), batch.fileSha256(),
                batch.fileName(), batch.status().name(), batch.totalRows(), batch.validRows(), batch.invalidRows(),
                ts(batch.createdAt()));
        for (ImportRow row : batch.rows()) {
            jdbc.update("""
                    INSERT INTO portfolio_import_row(batch_id,source_row_number,raw_json,error_code,safe_message)
                    VALUES(?,?,?,?,?)
                    """, batch.batchId(), row.sourceRowNumber(), row.rawJson(), row.errorCode(), row.safeMessage());
        }
    }

    /** 批次头查到后再装行。所有者或组合不符时为空。 */
    @Override
    public Optional<ImportBatch> findImportBatch(UserId owner, PortfolioId id, String batchId) {
        List<ImportBatch> batches = jdbc.query("""
                SELECT * FROM portfolio_import_batch
                WHERE batch_id=? AND owner_user_id=? AND portfolio_id=?
                """, this::batch, batchId, owner.value(), id.value());
        return batches.stream().findFirst().map(found -> {
            List<ImportRow> rows = jdbc.query(
                    "SELECT * FROM portfolio_import_row WHERE batch_id=? ORDER BY source_row_number",
                    this::row, found.batchId());
            return new ImportBatch(found.batchId(), found.portfolioId(), found.ownerUserId(), found.fileSha256(),
                    found.fileName(), found.status(), found.totalRows(), found.validRows(), found.invalidRows(),
                    found.createdAt(), found.committedAt(), rows);
        });
    }

    /** 标记已提交。提交时间取系统时钟。 */
    @Override
    public void markImportCommitted(String batchId) {
        jdbc.update("UPDATE portfolio_import_batch SET status=?,committed_at=? WHERE batch_id=?",
                ImportBatchStatus.COMMITTED.name(), ts(Instant.now()), batchId);
    }

    /** 行删除，批次头改成 DELETED，不物理删除批次。 */
    @Override
    public void deleteImportBatch(String batchId) {
        jdbc.update("DELETE FROM portfolio_import_row WHERE batch_id=?", batchId);
        jdbc.update("UPDATE portfolio_import_batch SET status=? WHERE batch_id=?",
                ImportBatchStatus.DELETED.name(), batchId);
    }

    /** 先清空该组合快照再插入。算法版本和覆盖状态在这里写死。 */
    @Override
    public void replacePositionSnapshots(PortfolioId portfolioId, UserId owner, LocalDate asOf,
            List<FundPosition> positions, String inputHash) {
        jdbc.update("DELETE FROM fund_position_snapshot WHERE portfolio_id=? AND owner_user_id=?",
                portfolioId.value(), owner.value());
        Instant now = Instant.now();
        for (FundPosition position : positions) {
            jdbc.update("""
                    INSERT INTO fund_position_snapshot(snapshot_id,portfolio_id,owner_user_id,fund_code,as_of_date,confirmed_shares,remaining_cost,realized_profit,cash_dividend,input_hash,algorithm_version,coverage_status,calculated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID().toString(), portfolioId.value(), owner.value(),
                    position.fundCode().value(), asOf, position.confirmedShares(), position.remainingCost(),
                    position.realizedProfit(), position.accumulatedCashDividend(), inputHash, "portfolio-position-v1",
                    "AVAILABLE", ts(now));
        }
    }

    /** 删除该用户在该组合下的全部快照。 */
    @Override
    public void deletePositionSnapshots(PortfolioId portfolioId, UserId owner) {
        jdbc.update("DELETE FROM fund_position_snapshot WHERE portfolio_id=? AND owner_user_id=?",
                portfolioId.value(), owner.value());
    }

    /** 计数为空时当 0。 */
    @Override
    public int countPositionSnapshots(PortfolioId portfolioId, UserId owner) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fund_position_snapshot WHERE portfolio_id=? AND owner_user_id=?",
                Integer.class, portfolioId.value(), owner.value());
        return count == null ? 0 : count;
    }

    /** 组合行。 */
    private UserPortfolio portfolio(ResultSet row, int ignored) throws SQLException {
        return new UserPortfolio(new PortfolioId(row.getString("portfolio_id")),
                new UserId(row.getString("owner_user_id")), row.getString("display_name"), row.getString("currency"),
                PortfolioStatus.valueOf(row.getString("status")), row.getLong("version"),
                instant(row.getTimestamp("created_at")), instant(row.getTimestamp("updated_at")));
    }

    /** 成交行。日期列按 LocalDate 读取。 */
    private FundTransaction transaction(ResultSet row, int ignored) throws SQLException {
        return new FundTransaction(row.getString("transaction_id"), new PortfolioId(row.getString("portfolio_id")),
                new UserId(row.getString("owner_user_id")), new FundCode(row.getString("fund_code")),
                TransactionType.valueOf(row.getString("transaction_type")), row.getObject("trade_date", LocalDate.class),
                row.getObject("confirm_date", LocalDate.class), row.getBigDecimal("shares"),
                row.getBigDecimal("gross_amount"), row.getBigDecimal("fee"), row.getBigDecimal("confirmed_nav"),
                row.getString("currency"), row.getString("source"), row.getString("idempotency_key"),
                row.getString("reverses_transaction_id"), instant(row.getTimestamp("created_at")));
    }

    /** 批次头先不带行。提交时间为空表示尚未提交。 */
    private ImportBatch batch(ResultSet row, int ignored) throws SQLException {
        Timestamp committed = row.getTimestamp("committed_at");
        return new ImportBatch(row.getString("batch_id"), new PortfolioId(row.getString("portfolio_id")),
                new UserId(row.getString("owner_user_id")), row.getString("file_sha256"), row.getString("file_name"),
                ImportBatchStatus.valueOf(row.getString("status")), row.getInt("total_rows"), row.getInt("valid_rows"),
                row.getInt("invalid_rows"), instant(row.getTimestamp("created_at")),
                committed == null ? null : instant(committed), List.of());
    }

    /** 导入行。错误码为空表示该行通过校验。 */
    private ImportRow row(ResultSet row, int ignored) throws SQLException {
        return new ImportRow(row.getInt("source_row_number"), row.getString("raw_json"), row.getString("error_code"),
                row.getString("safe_message"));
    }

    /** JDBC 时间戳。 */
    private static Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 时间列。 */
    private static Instant instant(Timestamp value) {
        return value.toInstant();
    }
}
