package com.jijing.fund.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 查询结果中的单个净值点。
 * 序列第一项没有前一样本，因此涨跌幅可以为空；日期或净值缺失时由上游构造失败，本类型不再校验。
 *
 * @param navDate 净值日期
 * @param unitNav 单位净值
 * @param accumulatedNav 累计净值，上游未提供时为空
 * @param dailyChangeRate 相对前一个样本的百分比涨跌，首项为空
 */
public record NavPointResult(LocalDate navDate, BigDecimal unitNav, BigDecimal accumulatedNav,
        BigDecimal dailyChangeRate) {
}
