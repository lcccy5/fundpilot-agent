package com.jijing.fund.domain.research.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Provider identity used by provenance, governance and metrics. */
public record ProviderId(String value) {
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,62}");

    public ProviderId {
        Objects.requireNonNull(value, "providerId must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("providerId must use lowercase letters, digits and hyphens");
        }
    }
}
