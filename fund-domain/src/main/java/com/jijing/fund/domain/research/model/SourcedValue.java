package com.jijing.fund.domain.research.model;

import java.util.Objects;

/** A business value together with its complete, typed lineage. */
public record SourcedValue<T>(T value, DataLineage lineage) {
    public SourcedValue {
        Objects.requireNonNull(value, "value must not be null");
        Objects.requireNonNull(lineage, "lineage must not be null");
    }
}
