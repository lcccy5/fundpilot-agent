package com.jijing.fund.analytics.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 某个日期上的一份正净值。
 * 日期和净值缺失，或净值不大于零时，不能进入收益率序列。相同日期和净值重复构造得到相等实例。
 */
public record NavObservation(LocalDate date, BigDecimal nav) {

    /**
     * 拒绝空日期、空净值和小于等于零的净值。
     * 日期或净值为 null 时抛出 {@link NullPointerException}；净值不大于零时抛出 {@link IllegalArgumentException}。不在这里检查日期是否落在请求区间内。
     */
    public NavObservation {
        Objects.requireNonNull(date, "date must not be null");
        Objects.requireNonNull(nav, "nav must not be null");
        if (nav.signum() <= 0) {
            throw new IllegalArgumentException("nav must be greater than zero");
        }
    }
}
