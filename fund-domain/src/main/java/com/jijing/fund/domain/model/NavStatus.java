package com.jijing.fund.domain.model;

/**
 * 净值记录的可信状态，按名称持久化；指标计算只使用正式确认和更正后的净值，估算净值不参与。
 * 未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum NavStatus {
    /** 基金公司正式公布的净值。 */
    CONFIRMED,
    /** 盘中或公布前的估算净值，不能当作正式净值使用。 */
    ESTIMATED,
    /** 正式公布后又被更正过的净值，以更正后的数值为准。 */
    CORRECTED
}
