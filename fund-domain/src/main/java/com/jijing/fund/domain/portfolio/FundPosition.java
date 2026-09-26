package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 组合中某只基金的持仓快照，由已确认交易汇总得到：确认份额、剩余成本、已实现收益、累计现金分红和最近确认日期。
 * 构造时不做任何校验，字段可为 null 或负数，调用方负责保证数值一致。
 */
public record FundPosition(FundCode fundCode, BigDecimal confirmedShares, BigDecimal remainingCost,
                           BigDecimal realizedProfit, BigDecimal accumulatedCashDividend, LocalDate lastConfirmedDate) {
    /**
     * 计算每份平均持仓成本（剩余成本 ÷ 确认份额），保留 8 位小数并四舍五入（HALF_UP）。
     * 份额为 0 时返回 0 而不是抛出除零异常；confirmedShares 为 null，或份额非 0 而 remainingCost 为 null 时抛出 NullPointerException。
     */
    public BigDecimal averageCostPerShare() {
        return confirmedShares.signum() == 0
                ? BigDecimal.ZERO
                : remainingCost.divide(confirmedShares, 8, java.math.RoundingMode.HALF_UP);
    }
}
