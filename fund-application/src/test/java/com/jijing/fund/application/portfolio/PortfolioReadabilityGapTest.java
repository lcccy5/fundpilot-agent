package com.jijing.fund.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.ImportBatch;
import com.jijing.fund.domain.portfolio.ImportBatchStatus;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.domain.portfolio.PortfolioStatus;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 组合用例里原先没有断言的失败路径：组合不存在、他人隔离、空文件和导入冲突。
 * 这些断言描述当前行为，仓库拒绝或投影失败时不会为了让测试通过而放宽生产代码。
 */
class PortfolioReadabilityGapTest {
    private final UserId ownerId = new UserId("00000000-0000-0000-0000-0000000000a1");
    private final UserId otherId = new UserId("00000000-0000-0000-0000-0000000000b2");
    private final AuthenticatedUser owner = new AuthenticatedUser(ownerId, Set.of(UserRole.USER), "owner");
    private final AuthenticatedUser other = new AuthenticatedUser(otherId, Set.of(UserRole.USER), "other");
    private final PortfolioId portfolioId = new PortfolioId("00000000-0000-0000-0000-00000000c0c0");

    /**
     * 组合标识没有任何记录时，读取、估值、导入和记账都按找不到拒绝，而不是返回空持仓。
     */
    @Test
    void missingPortfolioIsNotFound() {
        var service = service(new Memory());
        assertThatThrownBy(() -> service.transactions(owner, portfolioId)).isInstanceOf(PortfolioNotFoundException.class)
                .hasMessage("portfolio not found");
        assertThatThrownBy(() -> service.positions(owner, portfolioId)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.valuation(owner, portfolioId, LocalDate.of(2026, 1, 10)))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.returns(owner, portfolioId, null)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.risk(owner, portfolioId, null)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.rebuildSnapshots(owner, portfolioId)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.append(owner, portfolioId, null)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.previewImport(owner, portfolioId, "trades.csv", new byte[] {'a'}))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.importBatch(owner, portfolioId, "missing")).isInstanceOf(PortfolioNotFoundException.class)
                .hasMessage("import batch not found");
    }

    /**
     * 他人不能读取或提交属主的组合和导入批次。隔离由按用户查询实现，批次在拒绝后仍然留在属主名下。
     */
    @Test
    void otherUserCannotReadOrImportOwnedPortfolio() {
        var memory = new Memory();
        memory.savePortfolio(portfolio(ownerId));
        var service = service(memory);
        var batch = service.previewImport(owner, portfolioId, "trades.csv", csv("000001", "10"));

        assertThatThrownBy(() -> service.positions(other, portfolioId)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.valuation(other, portfolioId, LocalDate.of(2026, 1, 10)))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.transactions(other, portfolioId)).isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.previewImport(other, portfolioId, "trades.csv", csv("000001", "10")))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.commitImport(other, portfolioId, batch.batchId(), batch.fileSha256()))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThatThrownBy(() -> service.deleteImport(other, portfolioId, batch.batchId()))
                .isInstanceOf(PortfolioNotFoundException.class);
        assertThat(memory.findImportBatch(ownerId, portfolioId, batch.batchId())).isPresent();
        assertThat(service.list(other)).isEmpty();
        assertThat(service.list(owner)).hasSize(1);
    }

    /**
     * 空文件和缺失内容在预览时被拒绝。读取器对空内容单独失败，避免把空表当成成功导入。
     */
    @Test
    void emptyImportFileIsRejected() {
        var memory = new Memory();
        memory.savePortfolio(portfolio(ownerId));
        var service = service(memory);

        assertThatThrownBy(() -> service.previewImport(owner, portfolioId, "trades.csv", new byte[0]))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file must be 1 byte to 5 MB");
        assertThatThrownBy(() -> service.previewImport(owner, portfolioId, "trades.csv", null))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file must be 1 byte to 5 MB");
        assertThatThrownBy(() -> new CsvSpreadsheetTableReader().read("trades.csv", new byte[0]))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file is empty");
        assertThatThrownBy(() -> new CsvSpreadsheetTableReader().read("trades.csv", null))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("import file is empty");
    }

    /**
     * 未预览、摘要不一致、已提交后删除，以及预览通过但金额无法入账，都停止在冲突或提交失败，不写流水。
     */
    @Test
    void importConflictsStopBeforeTransactionsAreWritten() {
        var memory = new Memory();
        memory.savePortfolio(portfolio(ownerId));
        var service = service(memory);
        var preview = service.previewImport(owner, portfolioId, "trades.csv", csv("000001", "10"));

        assertThatThrownBy(() -> service.commitImport(owner, portfolioId, preview.batchId(), "not-the-hash"))
                .isInstanceOf(PortfolioConflictException.class)
                .hasMessage("file hash mismatch");
        assertThat(memory.findTransactions(portfolioId, ownerId)).isEmpty();

        memory.saveImportBatch(committed(preview.batchId()));
        assertThatThrownBy(() -> service.commitImport(owner, portfolioId, preview.batchId(), null))
                .isInstanceOf(PortfolioConflictException.class)
                .hasMessage("import batch is not previewed");
        assertThatThrownBy(() -> service.deleteImport(owner, portfolioId, preview.batchId()))
                .isInstanceOf(PortfolioConflictException.class)
                .hasMessage("committed import cannot be deleted");
        assertThat(memory.findImportBatch(ownerId, portfolioId, preview.batchId())).get()
                .extracting(ImportBatch::status).isEqualTo(ImportBatchStatus.COMMITTED);

        var invalidAmount = service.previewImport(owner, portfolioId, "bad.csv", csv("000001", "abc"));
        assertThat(invalidAmount.validRows()).isEqualTo(1);
        assertThatThrownBy(() -> service.commitImport(owner, portfolioId, invalidAmount.batchId(), null))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("committed row is invalid");
        assertThat(memory.findImportBatch(ownerId, portfolioId, invalidAmount.batchId())).get()
                .extracting(ImportBatch::status).isEqualTo(ImportBatchStatus.PREVIEWED);
        assertThat(memory.findTransactions(portfolioId, ownerId)).isEmpty();
    }

    /**
     * 扩展名通过了 xlsx 检查，但内容以压缩包魔数开头时，当前读取器仍拒绝该文件。
     */
    @Test
    void xlsxMagicIsRejectedByTheCsvReader() {
        var memory = new Memory();
        memory.savePortfolio(portfolio(ownerId));
        assertThatThrownBy(() -> service(memory).previewImport(owner, portfolioId, "book.xlsx", new byte[] {'P', 'K'}))
                .isInstanceOf(PortfolioException.class)
                .hasMessage("unsupported import file");
    }

    /**
     * 组装被测用例。净值仓库用空模拟，因为这些失败路径在取净值之前就返回。
     */
    private PortfolioApplicationService service(PortfolioRepository repository) {
        return new PortfolioApplicationService(repository, Mockito.mock(FundNavRepository.class), new PortfolioPositionProjector(),
                new MoneyWeightedReturnCalculator(), new TimeWeightedReturnCalculator(), new PortfolioConcentrationCalculator(),
                List.of(new CsvSpreadsheetTableReader()), null, Clock.systemUTC());
    }

    /**
     * 构造属主名下的活跃组合。标识非法时由组合标识值对象拒绝，本辅助方法只使用固定的合法标识。
     */
    private UserPortfolio portfolio(UserId userId) {
        return new UserPortfolio(portfolioId, userId, "长期", "CNY", PortfolioStatus.ACTIVE, 0, Instant.EPOCH, Instant.EPOCH);
    }

    /**
     * 生成一行申购。份额文本原样写入，非法数字不会在预览阶段被发现。
     */
    private static byte[] csv(String fundCode, String shares) {
        String text = "基金代码,交易类型,交易日期,确认日期,确认份额,交易金额,手续费,确认净值\n"
                + fundCode + ",申购,2026-01-02,2026-01-03," + shares + ",100,0,10\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 把已有批次改成已提交，用来触发状态冲突。批次号为空时后续查找会找不到，测试只传入预览产生的编号。
     */
    private ImportBatch committed(String batchId) {
        return new ImportBatch(batchId, portfolioId, ownerId, "abc", "trades.csv", ImportBatchStatus.COMMITTED, 0, 0, 0,
                Instant.EPOCH, Instant.EPOCH, List.of());
    }

    /**
     * 按用户保存组合、流水和导入批次。查找必须同时匹配用户，避免测试把空模拟的默认空结果误当成隔离。
     */
    private static final class Memory implements PortfolioRepository {
        private final Map<String, UserPortfolio> portfolios = new LinkedHashMap<>();
        private final List<FundTransaction> transactions = new ArrayList<>();
        private final Map<String, ImportBatch> batches = new LinkedHashMap<>();
        private final Map<String, Integer> snapshots = new HashMap<>();

        /**
         * 组合主键包含属主，同一标识在不同用户下互不可见。
         */
        private static String portfolioKey(PortfolioId id, UserId owner) {
            return id.value() + ":" + owner.value();
        }

        /**
         * 返回该用户的组合。用户为空时抛出空指针异常。
         */
        @Override
        public List<UserPortfolio> findByOwner(UserId owner) {
            return portfolios.values().stream().filter(portfolio -> portfolio.ownerUserId().equals(owner)).toList();
        }

        /**
         * 同时匹配标识和属主。任一不符时返回空，不泄露另一用户的组合。
         */
        @Override
        public Optional<UserPortfolio> findByIdAndOwner(PortfolioId id, UserId owner) {
            return Optional.ofNullable(portfolios.get(portfolioKey(id, owner)));
        }

        /**
         * 保存组合。组合为空时抛出空指针异常。
         */
        @Override
        public void savePortfolio(UserPortfolio portfolio) {
            portfolios.put(portfolioKey(portfolio.portfolioId(), portfolio.ownerUserId()), portfolio);
        }

        /**
         * 仅当当前版本等于期望值时推进版本。组合不存在或版本不符时返回 false，不修改原记录。
         */
        @Override
        public boolean updateVersion(PortfolioId id, UserId owner, long expectedVersion, long nextVersion) {
            var current = findByIdAndOwner(id, owner).orElse(null);
            if (current == null || current.version() != expectedVersion) {
                return false;
            }
            portfolios.put(portfolioKey(id, owner), new UserPortfolio(current.portfolioId(), current.ownerUserId(), current.displayName(),
                    current.currency(), current.status(), nextVersion, current.createdAt(), current.updatedAt()));
            return true;
        }

        /**
         * 按用户、组合和幂等键查找流水。没有命中时返回空。
         */
        @Override
        public Optional<FundTransaction> findByIdempotency(UserId owner, PortfolioId portfolioId, String idempotencyKey) {
            return transactions.stream()
                    .filter(transaction -> transaction.ownerUserId().equals(owner)
                            && transaction.portfolioId().equals(portfolioId)
                            && transaction.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        /**
         * 按用户和组合查找一笔流水。不存在时返回空。
         */
        @Override
        public Optional<FundTransaction> findTransaction(UserId owner, PortfolioId portfolioId, String transactionId) {
            return transactions.stream()
                    .filter(transaction -> transaction.ownerUserId().equals(owner)
                            && transaction.portfolioId().equals(portfolioId)
                            && transaction.transactionId().equals(transactionId))
                    .findFirst();
        }

        /**
         * 判断该用户是否已有指向原流水的补偿。原流水号为空时不会误匹配空引用，只比较相等的非空编号。
         */
        @Override
        public boolean hasReversal(UserId owner, String originalTransactionId) {
            return transactions.stream().anyMatch(transaction -> transaction.ownerUserId().equals(owner)
                    && originalTransactionId.equals(transaction.reversesTransactionId()));
        }

        /**
         * 追加流水。流水为空时抛出空指针异常。
         */
        @Override
        public void appendTransaction(FundTransaction transaction) {
            transactions.add(transaction);
        }

        /**
         * 返回该用户在该组合中的流水。其他用户的流水不会出现。
         */
        @Override
        public List<FundTransaction> findTransactions(PortfolioId portfolioId, UserId owner) {
            return transactions.stream()
                    .filter(transaction -> transaction.portfolioId().equals(portfolioId) && transaction.ownerUserId().equals(owner))
                    .toList();
        }

        /**
         * 按批次号保存导入批次，后写覆盖先写。批次为空时抛出空指针异常。
         */
        @Override
        public void saveImportBatch(ImportBatch batch) {
            batches.put(batch.batchId(), batch);
        }

        /**
         * 同时匹配用户、组合和批次号。属主不符时返回空。
         */
        @Override
        public Optional<ImportBatch> findImportBatch(UserId owner, PortfolioId portfolioId, String batchId) {
            var batch = batches.get(batchId);
            if (batch == null || !batch.ownerUserId().equals(owner) || !batch.portfolioId().equals(portfolioId)) {
                return Optional.empty();
            }
            return Optional.of(batch);
        }

        /**
         * 只按批次号标记提交，不再次核对用户。批次不存在时不做任何事。
         */
        @Override
        public void markImportCommitted(String batchId) {
            var batch = batches.get(batchId);
            if (batch == null) {
                return;
            }
            batches.put(batchId, new ImportBatch(batch.batchId(), batch.portfolioId(), batch.ownerUserId(), batch.fileSha256(),
                    batch.fileName(), ImportBatchStatus.COMMITTED, batch.totalRows(), batch.validRows(), batch.invalidRows(),
                    batch.createdAt(), Instant.EPOCH, batch.rows()));
        }

        /**
         * 只按批次号删除。批次不存在时不做任何事，也不报错。
         */
        @Override
        public void deleteImportBatch(String batchId) {
            batches.remove(batchId);
        }

        /**
         * 记录快照条数。持仓列表为空时记成零。
         */
        @Override
        public void replacePositionSnapshots(PortfolioId portfolioId, UserId owner, LocalDate asOf, List<FundPosition> positions, String inputHash) {
            snapshots.put(portfolioKey(portfolioId, owner), positions.size());
        }

        /**
         * 删除该用户该组合的快照计数。不存在时保持为空。
         */
        @Override
        public void deletePositionSnapshots(PortfolioId portfolioId, UserId owner) {
            snapshots.remove(portfolioKey(portfolioId, owner));
        }

        /**
         * 返回快照条数。还没有快照时返回零。
         */
        @Override
        public int countPositionSnapshots(PortfolioId portfolioId, UserId owner) {
            return snapshots.getOrDefault(portfolioKey(portfolioId, owner), 0);
        }
    }
}
