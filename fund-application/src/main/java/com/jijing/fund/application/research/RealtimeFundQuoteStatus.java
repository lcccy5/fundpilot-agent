package com.jijing.fund.application.research;

/**
 * 代理行情查询的业务状态。缺少数据时使用这些状态，而不是抛出异常。
 */
public enum RealtimeFundQuoteStatus {
    /**
     * 报价时间落在允许的年龄以内，结果中带有代理行情。
     */
    AVAILABLE,

    /**
     * 有报价，但报价时间早于允许的年龄，调用方不能把它当成刚刚成交的价格。
     */
    STALE,

    /**
     * 这只基金没有可用的关联场内 ETF，结果中不能带行情。
     */
    NO_EXCHANGE_PROXY,

    /**
     * 有关联标的，但报价器这次没有给出行情。结果中不能带行情。
     */
    DATA_NOT_READY
}
