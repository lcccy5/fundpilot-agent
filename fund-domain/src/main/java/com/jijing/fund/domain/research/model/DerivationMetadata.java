package com.jijing.fund.domain.research.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Metadata for a deterministic result calculated from source inputs. */
public record DerivationMetadata(String algorithmVersion, String ruleVersion, Instant calculatedAt,
                                 List<String> limitations) {
    public DerivationMetadata {
        if (algorithmVersion == null || algorithmVersion.isBlank()) throw new IllegalArgumentException("algorithmVersion is required");
        if (ruleVersion == null || ruleVersion.isBlank()) throw new IllegalArgumentException("ruleVersion is required");
        Objects.requireNonNull(calculatedAt, "calculatedAt must not be null");
        limitations = limitations == null ? List.of() : limitations.stream().filter(Objects::nonNull)
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
