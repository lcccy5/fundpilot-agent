package com.jijing.fund.analytics.model;

import java.math.BigDecimal;

public record MetricValue(MetricStatus status, BigDecimal value, String unavailableReason) {
    public static MetricValue available(BigDecimal value) { return new MetricValue(MetricStatus.AVAILABLE, value, null); }
    public static MetricValue unavailable(String reason) { return new MetricValue(MetricStatus.UNAVAILABLE, null, reason); }
}

