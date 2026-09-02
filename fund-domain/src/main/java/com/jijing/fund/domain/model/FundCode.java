package com.jijing.fund.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** 基金代码，当前仅接受中国公募基金常见的六码代码。 */
public record FundCode(String value) {
    private static final Pattern PATTERN = Pattern.compile("^\\d{6}$");

    public FundCode {
        Objects.requireNonNull(value, "fundCode must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("fundCode must be exactly 6 digits");
        }
    }
}

