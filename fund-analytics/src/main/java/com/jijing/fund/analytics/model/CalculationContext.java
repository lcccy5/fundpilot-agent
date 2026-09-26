package com.jijing.fund.analytics.model;

import java.math.BigDecimal;
import java.time.Clock;

/**
 * 提供无风险利率、年化交易日数、各类最小样本、算法版本和计算时钟。
 * 不校验交易日数是否为正，也不拒绝 null 时钟或负的最小样本。非法参数要到具体公式里才表现为不可用结果或空指针。相同参数重复构造相等。
 */
public record CalculationContext(BigDecimal annualRiskFreeRate, int tradingDaysPerYear,
        int minVolatilityReturns, int minSharpeReturns, int minAnnualizationDays,
        String algorithmVersion, Clock clock) {

    /**
     * 组装零无风险利率、252 个交易日、波动至少 20 个收益、夏普至少 60 个收益、年化至少 365 天和第一版算法的上下文。
     * 时钟为 null 时不会在这里失败，第一次读取计算时刻才会抛出 {@link NullPointerException}。同一时钟重复调用得到相等上下文。
     */
    public static CalculationContext defaults(Clock clock) {
        return new CalculationContext(BigDecimal.ZERO, 252, 20, 60, 365, "fund-metrics-v1", clock);
    }
}
