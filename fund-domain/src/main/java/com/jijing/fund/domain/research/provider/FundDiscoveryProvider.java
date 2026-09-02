package com.jijing.fund.domain.research.provider;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.LinkedExchangeFund;
import java.util.Optional;

public interface FundDiscoveryProvider {
    Optional<LinkedExchangeFund> findLinkedExchangeFund(FundCode fundCode);
}
