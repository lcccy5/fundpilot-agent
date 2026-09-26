package com.jijing.fund.domain.research.model;

import com.jijing.fund.domain.model.FundCode;
import java.util.Objects;

/** 与某只场外基金关联的场内交易基金（如联接基金对应的 ETF），其行情只能作为该基金的参考代理，不能当作官方净值。 */
public record LinkedExchangeFund(FundCode fundCode, ExchangeSecurityCode securityCode, String displayName,
                                 DataProvenance provenance) {
    /**
     * 校验关联关系的必填项。
     * fundCode、securityCode、provenance 为 null 时抛出 NullPointerException；displayName 为 null 或空白时抛出 IllegalArgumentException。
     */
    public LinkedExchangeFund {
        Objects.requireNonNull(fundCode, "fundCode must not be null");
        Objects.requireNonNull(securityCode, "securityCode must not be null");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        Objects.requireNonNull(provenance, "provenance must not be null");
    }
}
