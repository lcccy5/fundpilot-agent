package com.jijing.fund.domain.research.model;

import java.util.Objects;

/** 业务值与其完整、带类型的数据血缘的组合，保证任何对外返回的研究数据都能追溯来源。 */
public record SourcedValue<T>(T value, DataLineage lineage) {
    /** 校验值和血缘都存在：value 或 lineage 为 null 时抛出 NullPointerException，不允许“无值但有来源”的结果。 */
    public SourcedValue {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(lineage, "lineage must not be null");
    }
}
