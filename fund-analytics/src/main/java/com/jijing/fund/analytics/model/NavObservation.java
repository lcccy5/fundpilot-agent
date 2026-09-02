package com.jijing.fund.analytics.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record NavObservation(LocalDate date, BigDecimal nav) {
    public NavObservation {
        Objects.requireNonNull(date, "date must not be null"); Objects.requireNonNull(nav, "nav must not be null");
        if (nav.signum() <= 0) throw new IllegalArgumentException("nav must be greater than zero");
    }
}

