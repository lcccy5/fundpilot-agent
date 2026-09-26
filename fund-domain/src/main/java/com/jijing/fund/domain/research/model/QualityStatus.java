package com.jijing.fund.domain.research.model;

/**
 * 随数据一起返回的质量状态，用来告诉调用方数据可信到什么程度；它不是异常分类，质量差的数据仍会正常返回。
 * 未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum QualityStatus {
    /** 已通过校验，可直接使用。 */
    VERIFIED,
    /** 只有部分数据可用。 */
    PARTIAL,
    /** 数据已过时（例如行情超过新鲜度阈值）。 */
    STALE,
    /** 数据源当前无法提供该数据。 */
    UNAVAILABLE
}
