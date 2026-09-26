package com.jijing.fund.domain.research.model;

/**
 * 市场数据值的稳定业务含义，用于区分“官方净值”“估算净值”“场内行情”等不可混用的数据；
 * 必须由数据本身的语义决定，绝不能根据提供方名称推断。未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum MarketDataKind {
    /** 基金公司公布的官方场外净值。 */
    OFFICIAL_FUND_NAV,
    /** 第三方估算的场外基金净值。 */
    ESTIMATED_FUND_NAV,
    /** 交易所实时成交行情。 */
    EXCHANGE_TRADED_QUOTE,
    /** 以关联 ETF 行情作为场外基金的代理参考。 */
    UNDERLYING_ETF_PROXY,
    /** ETF 每日申购赎回清单（PCF）中的成分篮子。 */
    DAILY_PCF_BASKET,
    /** 定期报告披露的基金持仓。 */
    DISCLOSED_FUND_HOLDING,
    /** 上市公司公告。 */
    COMPANY_ANNOUNCEMENT,
    /** 由上述数据确定性计算得到的分析结果。 */
    DERIVED_ANALYTICS
}
