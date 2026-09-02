package com.jijing.fund.domain.research.model;

import com.jijing.fund.domain.model.FundCode;
import java.util.Objects;

/** Exchange-traded fund that can be used only as a proxy for a linked fund. */
public record LinkedExchangeFund(FundCode fundCode, ExchangeSecurityCode securityCode, String displayName,
                                 DataProvenance provenance) {
    public LinkedExchangeFund {
        Objects.requireNonNull(fundCode, "fundCode must not be null");
        Objects.requireNonNull(securityCode, "securityCode must not be null");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        Objects.requireNonNull(provenance, "provenance must not be null");
    }
}
