package com.jijing.fund.application.research;

import com.jijing.fund.domain.research.model.MarketDataKind;
import java.math.BigDecimal;
import java.time.Instant;

/** Explicitly names an ETF proxy instead of exposing it as fund NAV. */
public record RealtimeFundQuoteView(String fundCode, String proxyEtfCode, String proxyEtfName,
                                    BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceChange,
                                    BigDecimal changePercent, Instant quoteTime, MarketDataKind dataKind,
                                    String disclaimer) {
    public RealtimeFundQuoteView {
        if (dataKind != MarketDataKind.UNDERLYING_ETF_PROXY) {
            throw new IllegalArgumentException("realtime fund quote must be an UNDERLYING_ETF_PROXY");
        }
    }
}
