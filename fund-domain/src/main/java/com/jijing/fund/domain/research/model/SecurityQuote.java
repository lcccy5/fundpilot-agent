package com.jijing.fund.domain.research.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** A real-time exchange-traded quote, never an official off-exchange fund NAV. */
public record SecurityQuote(ExchangeSecurityCode securityCode, String securityName, BigDecimal currentPrice,
                            BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                            Instant quoteTime, DataProvenance provenance) {
    public SecurityQuote {
        Objects.requireNonNull(securityCode, "securityCode must not be null");
        if (securityName == null || securityName.isBlank()) throw new IllegalArgumentException("securityName is required");
        Objects.requireNonNull(currentPrice, "currentPrice must not be null");
        Objects.requireNonNull(quoteTime, "quoteTime must not be null");
        Objects.requireNonNull(provenance, "provenance must not be null");
        if (currentPrice.signum() <= 0) throw new IllegalArgumentException("currentPrice must be positive");
        if (provenance.dataKind() != MarketDataKind.EXCHANGE_TRADED_QUOTE) {
            throw new IllegalArgumentException("security quote provenance must be EXCHANGE_TRADED_QUOTE");
        }
    }
}
