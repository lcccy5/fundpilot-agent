package com.jijing.fund.domain.research.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Chinese exchange security code with a normalized sh/sz exchange prefix. */
public record ExchangeSecurityCode(String exchange, String code) {
    private static final Pattern CODE = Pattern.compile("\\d{6}");

    public ExchangeSecurityCode {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(code, "code must not be null");
        exchange = exchange.trim().toLowerCase(Locale.ROOT);
        if (!exchange.equals("sh") && !exchange.equals("sz")) throw new IllegalArgumentException("exchange must be sh or sz");
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("security code must be 6 digits");
    }

    public String providerSymbol() { return exchange + code; }
}
