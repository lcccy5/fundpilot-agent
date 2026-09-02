package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FundPosition(FundCode fundCode,BigDecimal confirmedShares,BigDecimal remainingCost,
                           BigDecimal realizedProfit,BigDecimal accumulatedCashDividend,LocalDate lastConfirmedDate) {
    public BigDecimal averageCostPerShare(){return confirmedShares.signum()==0?BigDecimal.ZERO:remainingCost.divide(confirmedShares,8,java.math.RoundingMode.HALF_UP);}
}
