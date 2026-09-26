package com.jijing.fund.analytics.model;

import java.time.LocalDate;

/**
 * 最大回撤对应的峰值日、谷值日和收复日。
 * 尚未收复时收复日为 null。不校验峰值是否早于谷值，也不拒绝三个日期都相同的零回撤区间。
 */
public record DrawdownPeriod(LocalDate peakDate, LocalDate troughDate, LocalDate recoveryDate) {}
