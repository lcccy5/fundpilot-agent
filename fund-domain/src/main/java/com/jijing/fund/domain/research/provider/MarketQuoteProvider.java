package com.jijing.fund.domain.research.provider;

import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.SecurityQuote;
import java.util.Optional;

/** 场内行情端口，负责获取交易所证券的最新实时行情。 */
public interface MarketQuoteProvider {
    /**
     * 获取指定证券的最新行情；数据源没有该证券的行情时返回空。
     * 数据源不可用或返回的数据无法构造成合法 {@link SecurityQuote} 时，实现应抛出
     * {@link com.jijing.fund.domain.exception.ExternalDataSourceException}。
     */
    Optional<SecurityQuote> latestQuote(ExchangeSecurityCode securityCode);
}
