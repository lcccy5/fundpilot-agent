package com.jijing.fund.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record NavPoint(
        FundCode fundCode,
        LocalDate navDate,
        BigDecimal unitNav,
        BigDecimal accumulatedNav,
        BigDecimal adjustedNav,
        NavStatus navStatus,
        String dataSource,
        java.time.Instant sourceUpdatedAt,
        java.time.Instant collectedAt) {
    public NavPoint {
        Objects.requireNonNull(fundCode, "fundCode must not be null");
        Objects.requireNonNull(navDate, "navDate must not be null");
        Objects.requireNonNull(unitNav, "unitNav must not be null");
        if (unitNav.signum() <= 0) throw new IllegalArgumentException("unitNav must be greater than zero");
        if (accumulatedNav != null && accumulatedNav.signum() <= 0) throw new IllegalArgumentException("accumulatedNav must be greater than zero");
        if (adjustedNav != null && adjustedNav.signum() <= 0) throw new IllegalArgumentException("adjustedNav must be greater than zero");
        Objects.requireNonNull(navStatus, "navStatus must not be null");
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
    }
}
