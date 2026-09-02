package com.jijing.fund.domain.research.model;

/** Stable business meaning of a market-data value. Never infer this from a provider name. */
public enum MarketDataKind {
    OFFICIAL_FUND_NAV,
    ESTIMATED_FUND_NAV,
    EXCHANGE_TRADED_QUOTE,
    UNDERLYING_ETF_PROXY,
    DAILY_PCF_BASKET,
    DISCLOSED_FUND_HOLDING,
    COMPANY_ANNOUNCEMENT,
    DERIVED_ANALYTICS
}
