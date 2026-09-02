package com.jijing.fund.application.portfolio;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator.CashFlow;
import com.jijing.fund.domain.portfolio.PortfolioId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A money-weighted return is returned only when the dated cash-flow equation has a solution. */
public record PortfolioReturn(PortfolioId portfolioId, LocalDate asOfDate, BigDecimal moneyWeightedReturn,
                              BigDecimal timeWeightedReturn, String returnStatus, List<CashFlow> cashFlows, List<String> warnings,
                              String algorithmVersion) {}
