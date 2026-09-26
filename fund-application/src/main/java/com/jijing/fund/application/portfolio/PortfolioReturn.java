package com.jijing.fund.application.portfolio;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator.CashFlow;
import com.jijing.fund.domain.portfolio.PortfolioId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 组合在基准日的收益。资金加权收益只在现金流方程有解时非空；无解或净值未就绪时对应数字为空，并由 returnStatus 说明。
 * 时间加权为空不一定让状态变成不可用。记录不校验状态与数字是否一致，警告列表允许为空。
 */
public record PortfolioReturn(PortfolioId portfolioId, LocalDate asOfDate, BigDecimal moneyWeightedReturn,
        BigDecimal timeWeightedReturn, String returnStatus, List<CashFlow> cashFlows, List<String> warnings,
        String algorithmVersion) {
}
