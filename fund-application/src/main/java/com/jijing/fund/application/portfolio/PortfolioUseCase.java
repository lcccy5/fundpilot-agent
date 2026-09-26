package com.jijing.fund.application.portfolio;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.ImportBatch;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import java.time.LocalDate;
import java.util.List;

/**
 * 组合账本的应用边界。实现必须按当前用户隔离数据；组合或批次不存在时失败，不能返回别人的记录。
 * 用户或组合标识为空属于调用错误，允许以运行时异常失败。
 */
public interface PortfolioUseCase {
    /**
     * 列出当前用户的组合。用户为空时失败。
     */
    List<UserPortfolio> list(AuthenticatedUser actor);

    /**
     * 创建组合。名称为空、全空白或过长时失败，不保存。
     */
    UserPortfolio create(AuthenticatedUser actor, String name);

    /**
     * 追加一笔流水。命令不完整、组合已归档或不属于该用户时失败；相同幂等键应返回已有流水。
     */
    FundTransaction append(AuthenticatedUser actor, PortfolioId id, TransactionCommand command);

    /**
     * 冲正一笔流水。幂等键为空、原流水不存在、原流水已是冲正或已经冲正过时失败。
     */
    FundTransaction reverse(AuthenticatedUser actor, PortfolioId id, String transactionId, String idempotencyKey);

    /**
     * 读取流水。组合不存在或不属于该用户时失败。
     */
    List<FundTransaction> transactions(AuthenticatedUser actor, PortfolioId id);

    /**
     * 读取由流水投影出的持仓。组合不存在或不属于该用户时失败。
     */
    List<FundPosition> positions(AuthenticatedUser actor, PortfolioId id);

    /**
     * 按当前流水重建持仓快照。组合不存在时失败。
     */
    SnapshotRebuildResult rebuildSnapshots(AuthenticatedUser actor, PortfolioId id);

    /**
     * 估算基准日市值。基准日为空表示今天。净值缺失时结果必须显式标出缺口，不能把缺失当成零市值。
     */
    PortfolioValuation valuation(AuthenticatedUser actor, PortfolioId id, LocalDate asOf);

    /**
     * 计算基准日收益。净值不齐时收益为空并标明未就绪，不能把失败算成零收益。
     */
    PortfolioReturn returns(AuthenticatedUser actor, PortfolioId id, LocalDate asOf);

    /**
     * 计算持仓集中度。市值不齐时覆盖度必须标成部分，不能伪装成完整结果。
     */
    PortfolioRiskView risk(AuthenticatedUser actor, PortfolioId id, LocalDate asOf);

    /**
     * 预览导入文件并保存批次。空文件、超限文件、不支持的扩展名或无法读取时失败；行级错误留在批次里。
     */
    ImportBatch previewImport(AuthenticatedUser actor, PortfolioId id, String fileName, byte[] content);

    /**
     * 读取导入批次。批次不存在或不属于该用户的该组合时失败。
     */
    ImportBatch importBatch(AuthenticatedUser actor, PortfolioId id, String batchId);

    /**
     * 提交已预览的批次。状态不对或文件摘要不一致时失败，不能把无效行写成流水。
     */
    ImportBatch commitImport(AuthenticatedUser actor, PortfolioId id, String batchId, String fileSha256);

    /**
     * 删除未提交的批次。已提交的批次必须拒绝删除。
     */
    void deleteImport(AuthenticatedUser actor, PortfolioId id, String batchId);
}
