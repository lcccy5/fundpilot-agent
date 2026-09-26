package com.jijing.fund.analytics.model;

import java.math.BigDecimal;

/**
 * 携带一项指标的状态、数值和不可用原因。
 * 可用时原因应为 null，不可用时数值应为 null。记录本身不交叉校验这三项，直接构造可以打破约定。组件访问器由记录生成。
 */
public record MetricValue(MetricStatus status, BigDecimal value, String unavailableReason) {

    /**
     * 组装带数值、无原因码的可用指标。
     * 不拒绝 null 数值；把 null 标成可用后，调用方读数值时会遇到空指针。同一数值重复调用得到相等实例。
     */
    public static MetricValue available(BigDecimal value) {
        return new MetricValue(MetricStatus.AVAILABLE, value, null);
    }

    /**
     * 组装无数值、带原因码的不可用指标。
     * 不拒绝 null 或空白原因；原因缺失时调用方无法区分失败类型。同一原因重复调用得到相等实例。
     */
    public static MetricValue unavailable(String reason) {
        return new MetricValue(MetricStatus.UNAVAILABLE, null, reason);
    }
}
