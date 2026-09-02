package com.jijing.fund.analytics.model;

import java.math.BigDecimal;
import java.time.Clock;

public record CalculationContext(BigDecimal annualRiskFreeRate, int tradingDaysPerYear,
        int minVolatilityReturns, int minSharpeReturns, int minAnnualizationDays,
        String algorithmVersion, Clock clock) {
    public static CalculationContext defaults(Clock clock) {
        return new CalculationContext(BigDecimal.ZERO, 252, 20, 60, 365, "fund-metrics-v1", clock);
    }
}

