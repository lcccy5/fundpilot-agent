package com.jijing.fund.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 某只基金在某个净值日的一条净值记录，包含单位净值、累计净值、复权净值、净值状态以及来源与采集时间。
 * 累计净值和复权净值允许缺失，但一旦提供必须为正数。
 */
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
    /**
     * 按字段顺序依次校验净值记录，遇到第一个不合法字段即失败。
     * fundCode、navDate、unitNav、navStatus、dataSource、collectedAt 为 null 时抛出 NullPointerException；
     * unitNav 不大于 0、或者提供了不大于 0 的累计净值/复权净值时抛出 IllegalArgumentException。
     */
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
