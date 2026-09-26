package com.jijing.fund.domain.research.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** 场内证券的一条实时行情，只表示交易所成交价，绝不能当作场外基金的官方净值使用。 */
public record SecurityQuote(ExchangeSecurityCode securityCode, String securityName, BigDecimal currentPrice,
                            BigDecimal previousClose, BigDecimal priceChange, BigDecimal changePercent,
                            Instant quoteTime, DataProvenance provenance) {
    /**
     * 按字段顺序校验行情，遇到第一个不合法字段即失败。
     * securityCode、currentPrice、quoteTime、provenance 为 null 时抛出 NullPointerException；securityName 为 null/空白、
     * currentPrice 不大于 0、或来源的数据含义不是 {@link MarketDataKind#EXCHANGE_TRADED_QUOTE} 时抛出 IllegalArgumentException。
     * 昨收、涨跌额、涨跌幅允许为 null，且不与现价做一致性校验。
     */
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
