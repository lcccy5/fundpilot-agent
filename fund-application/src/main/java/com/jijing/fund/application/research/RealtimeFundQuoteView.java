package com.jijing.fund.application.research;

import com.jijing.fund.domain.research.model.MarketDataKind;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 一只基金对应的场内 ETF 代理行情。价格来自代理标的，不是场外基金的官方净值或盘中估值。
 */
public record RealtimeFundQuoteView(String fundCode, String proxyEtfCode, String proxyEtfName,
        BigDecimal currentPrice, BigDecimal previousClose, BigDecimal priceChange,
        BigDecimal changePercent, Instant quoteTime, MarketDataKind dataKind,
        String disclaimer) {
    /**
     * 拒绝把其他行情种类装进本视图。dataKind 不是场内 ETF 代理时抛出参数异常。
     * 代码、价格和时间不在这里检查，空值会被原样保存。
     */
    public RealtimeFundQuoteView {
        if (dataKind != MarketDataKind.UNDERLYING_ETF_PROXY) {
            throw new IllegalArgumentException("realtime fund quote must be an UNDERLYING_ETF_PROXY");
        }
    }
}
