package com.jijing.fund.analytics.model;

import java.math.*;

public record DataCoverage(CoverageStatus status, int actualObservations, int expectedTradingDays, BigDecimal rate) {
    public static DataCoverage of(int actual, int expected) {
        if (expected <= 0) return new DataCoverage(CoverageStatus.UNKNOWN, actual, expected, null);
        BigDecimal rate = BigDecimal.valueOf(actual).divide(BigDecimal.valueOf(expected), 8, RoundingMode.HALF_UP);
        CoverageStatus status = rate.compareTo(new BigDecimal("0.98")) >= 0 ? CoverageStatus.COMPLETE
                : rate.compareTo(new BigDecimal("0.90")) >= 0 ? CoverageStatus.PARTIAL : CoverageStatus.INSUFFICIENT;
        return new DataCoverage(status, actual, expected, rate);
    }
}

