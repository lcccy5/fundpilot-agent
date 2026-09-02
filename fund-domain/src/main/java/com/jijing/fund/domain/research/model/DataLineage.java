package com.jijing.fund.domain.research.model;

import java.util.List;
import java.util.Objects;

/** Inputs and optional deterministic derivation behind a returned value. */
public record DataLineage(List<DataProvenance> inputs, DerivationMetadata derivation) {
    public DataLineage {
        inputs = inputs == null ? List.of() : inputs.stream().filter(Objects::nonNull).toList();
        if (inputs.isEmpty()) throw new IllegalArgumentException("data lineage requires at least one input");
    }
}
