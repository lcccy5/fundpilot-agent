package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.LocalDate;
import java.util.*;

/**
 * 投资组合的持久化端口，覆盖组合本身、交易流水、导入批次和持仓快照。
 * 除按批次标识操作的方法外，所有查询都同时按所属用户过滤，防止跨用户读取；接口不校验参数，null 的处理由实现决定。
 */
public interface PortfolioRepository {
    /** 列出某用户拥有的全部组合；没有组合时返回空列表而不是 null。 */
    List<UserPortfolio> findByOwner(UserId owner);

    /** 查找属于该用户的指定组合；组合不存在或属于其他用户时都返回空，不区分两种情况。 */
    Optional<UserPortfolio> findByIdAndOwner(PortfolioId id, UserId owner);

    /** 新建组合；组合标识重复时由实现抛出存储层异常。 */
    void savePortfolio(UserPortfolio portfolio);

    /**
     * 乐观锁更新组合版本：仅当当前版本等于 expectedVersion 时改为 nextVersion。
     * 返回 false 表示版本已被并发修改或组合不属于该用户，调用方应视为冲突而不是重试同一个 expectedVersion。
     */
    boolean updateVersion(PortfolioId id, UserId owner, long expectedVersion, long nextVersion);

    /** 按幂等键查找已提交的交易，用于识别重复提交；未找到时返回空。 */
    Optional<FundTransaction> findByIdempotency(UserId owner, PortfolioId portfolioId, String idempotencyKey);

    /** 在该用户的指定组合内按交易标识查找交易；不存在或不属于该用户时返回空。 */
    Optional<FundTransaction> findTransaction(UserId owner, PortfolioId portfolioId, String transactionId);

    /** 判断某笔原交易是否已经被该用户冲正过，用于阻止重复冲正。 */
    boolean hasReversal(UserId owner, String originalTransactionId);

    /** 追加一笔交易流水；交易标识或幂等键重复时由实现抛出存储层异常，不会覆盖已有交易。 */
    void appendTransaction(FundTransaction transaction);

    /** 按确认日期顺序列出组合内的全部交易；组合不属于该用户时返回空列表。 */
    List<FundTransaction> findTransactions(PortfolioId portfolioId, UserId owner);

    /** 保存导入批次及其全部导入行；批次标识重复时由实现抛出存储层异常。 */
    void saveImportBatch(ImportBatch batch);

    /** 查找属于该用户和组合的导入批次（含导入行）；不存在或不属于该用户时返回空。 */
    Optional<ImportBatch> findImportBatch(UserId owner, PortfolioId portfolioId, String batchId);

    /** 把导入批次标记为已提交并记录提交时间；只按批次标识定位，调用方须先校验归属。批次不存在时不生效。 */
    void markImportCommitted(String batchId);

    /** 删除导入批次的导入行并把批次标记为已删除；只按批次标识定位，调用方须先校验归属。可重复调用。 */
    void deleteImportBatch(String batchId);

    /** 用新计算的持仓整体替换组合当前的持仓快照，并记录计算基准日和输入指纹；positions 为空列表时相当于清空快照。 */
    void replacePositionSnapshots(PortfolioId portfolioId, UserId owner, LocalDate asOf, List<FundPosition> positions, String inputHash);

    /** 删除组合的全部持仓快照；没有快照时不报错，可重复调用。 */
    void deletePositionSnapshots(PortfolioId portfolioId, UserId owner);

    /** 统计组合当前的持仓快照条数；没有快照或组合不属于该用户时返回 0。 */
    int countPositionSnapshots(PortfolioId portfolioId, UserId owner);
}
