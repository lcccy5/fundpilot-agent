package com.jijing.fund.application.portfolio;
import com.jijing.fund.domain.portfolio.*;import java.math.BigDecimal;import java.time.LocalDate;import java.util.*;
public record PortfolioValuation(PortfolioId portfolioId,LocalDate asOfDate,BigDecimal totalCost,BigDecimal totalValue,BigDecimal unrealizedProfit,List<PositionValue> positions,List<String> warnings,String algorithmVersion) {
    public record PositionValue(FundPosition position,BigDecimal nav,LocalDate navDate,BigDecimal value,String coverageStatus){}
}
