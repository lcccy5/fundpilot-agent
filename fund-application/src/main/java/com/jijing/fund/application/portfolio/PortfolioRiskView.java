package com.jijing.fund.application.portfolio;
import com.jijing.fund.domain.portfolio.PortfolioId;import java.math.BigDecimal;import java.time.LocalDate;import java.util.List;
public record PortfolioRiskView(PortfolioId portfolioId,LocalDate asOfDate,BigDecimal maxFundWeight,BigDecimal top3Weight,BigDecimal hhi,String concentrationStatus,String coverage,List<String> warnings,String algorithmVersion,String disclosureDate,String providerId){}
