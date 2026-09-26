package com.jijing.fund.application.portfolio;

import com.jijing.fund.domain.portfolio.PortfolioId;

/**
 * 一次持仓快照重建的结果。inputHash 标识本次写入快照所用的输入，positionCount 是投影出的持仓数，
 * persistedCount 是仓库回报的快照条数，二者不一致时调用方应视为仓库没有按投影完整落库。记录不校验哈希是否为空。
 */
public record SnapshotRebuildResult(PortfolioId portfolioId, String inputHash, int positionCount, int persistedCount) {
}
