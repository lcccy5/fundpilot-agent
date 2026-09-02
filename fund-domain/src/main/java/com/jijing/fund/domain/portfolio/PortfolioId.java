package com.jijing.fund.domain.portfolio;

import java.util.UUID;

public record PortfolioId(String value) {
    public PortfolioId { if(value==null||value.isBlank())throw new IllegalArgumentException("portfolioId is required"); UUID.fromString(value); }
    public static PortfolioId random(){return new PortfolioId(UUID.randomUUID().toString());}
}
