package com.jijing.fund.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

public record FundProfile(
        FundCode code,
        String name,
        String fundType,
        String managementCompany,
        String fundManager,
        LocalDate establishedDate,
        String dataSource,
        Instant sourceUpdatedAt,
        Instant collectedAt) {
    public FundProfile {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("fund name must not be blank");
        Objects.requireNonNull(dataSource, "dataSource must not be null");
        Objects.requireNonNull(collectedAt, "collectedAt must not be null");
    }
}
