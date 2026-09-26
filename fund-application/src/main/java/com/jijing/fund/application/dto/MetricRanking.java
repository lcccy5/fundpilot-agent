package com.jijing.fund.application.dto;

import java.math.BigDecimal;

/**
 * 对比结果中某一指标的一名基金。
 * 指标不可用时数值为空，原因写入不可用说明；对比整体无法成立时不会产生排名。
 *
 * @param rank 从一开始的名次，不可用指标排在可用指标之后
 * @param fundCode 基金代码
 * @param value 指标数值，不可用时为空
 * @param unavailableReason 指标不可用的原因，可用时为空
 */
public record MetricRanking(int rank, String fundCode, BigDecimal value, String unavailableReason) {
}
