package com.jijing.fund.analytics.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 记录实际观测数、预期交易日、覆盖率和由此决定的覆盖状态。
 * 预期交易日小于等于零时状态为未知且覆盖率为 null。阈值是 0.98 与 0.90。相同输入重复计算得到相等结果。
 */
public record DataCoverage(CoverageStatus status, int actualObservations, int expectedTradingDays, BigDecimal rate) {

    /**
     * 用实际点数除以预期交易日决定覆盖状态。
     * 预期小于等于零时返回未知状态和 null 覆盖率，不抛出除零异常。覆盖率按 8 位小数四舍五入；等于 0.98 视为完整，等于 0.90 视为部分，再低则为不足。
     * 不拒绝负的实际点数。同一对输入重复调用结果相等。
     */
    public static DataCoverage of(int actual, int expected) {
        if (expected <= 0) {
            return new DataCoverage(CoverageStatus.UNKNOWN, actual, expected, null);
        }
        BigDecimal rate = BigDecimal.valueOf(actual).divide(BigDecimal.valueOf(expected), 8, RoundingMode.HALF_UP);
        CoverageStatus status;
        if (rate.compareTo(new BigDecimal("0.98")) >= 0) {
            status = CoverageStatus.COMPLETE;
        } else if (rate.compareTo(new BigDecimal("0.90")) >= 0) {
            status = CoverageStatus.PARTIAL;
        } else {
            status = CoverageStatus.INSUFFICIENT;
        }
        return new DataCoverage(status, actual, expected, rate);
    }
}
