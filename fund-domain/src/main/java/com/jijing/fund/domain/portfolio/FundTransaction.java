package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.*;

public record FundTransaction(String transactionId,PortfolioId portfolioId,UserId ownerUserId,FundCode fundCode,
                              TransactionType transactionType,LocalDate tradeDate,LocalDate confirmDate,
                              BigDecimal shares,BigDecimal grossAmount,BigDecimal fee,BigDecimal confirmedNav,
                              String currency,String source,String idempotencyKey,String reversesTransactionId,
                              Instant createdAt) {
    public FundTransaction {
        if(transactionId==null||transactionId.isBlank()||tradeDate==null||confirmDate==null||transactionType==null||idempotencyKey==null||idempotencyKey.isBlank())throw new IllegalArgumentException("transaction is invalid");
        shares=zero(shares);grossAmount=zero(grossAmount);fee=zero(fee);
        if(shares.signum()<0||grossAmount.signum()<0||fee.signum()<0)throw new IllegalArgumentException("transaction amounts cannot be negative");
    }
    private static BigDecimal zero(BigDecimal value){return value==null?BigDecimal.ZERO:value;}
}
