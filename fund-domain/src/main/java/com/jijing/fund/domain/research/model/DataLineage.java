package com.jijing.fund.domain.research.model;

import java.util.List;
import java.util.Objects;

/** 一个返回值背后的数据血缘：它依赖的全部输入来源，以及可选的确定性推导过程说明。 */
public record DataLineage(List<DataProvenance> inputs, DerivationMetadata derivation) {
    /**
     * 规范化并校验输入来源：过滤掉 null 元素后拷贝为不可变列表。
     * inputs 为 null、空列表或只含 null 元素时抛出 IllegalArgumentException；derivation 允许为 null（表示直接取自来源、未经推导）。
     */
    public DataLineage {
        inputs = inputs == null ? List.of() : inputs.stream().filter(Objects::nonNull).toList();
        if (inputs.isEmpty()) throw new IllegalArgumentException("data lineage requires at least one input");
    }
}
