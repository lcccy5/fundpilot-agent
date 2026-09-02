package com.jijing.fund.domain.research.provider;

import com.jijing.fund.domain.research.model.ExchangeSecurityCode;
import com.jijing.fund.domain.research.model.SecurityQuote;
import java.util.Optional;

public interface MarketQuoteProvider {
    Optional<SecurityQuote> latestQuote(ExchangeSecurityCode securityCode);
}
