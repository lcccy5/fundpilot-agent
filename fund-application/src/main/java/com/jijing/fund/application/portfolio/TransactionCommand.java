package com.jijing.fund.application.portfolio;
import com.jijing.fund.domain.portfolio.TransactionType;import java.math.BigDecimal;import java.time.LocalDate;
public record TransactionCommand(String fundCode,TransactionType type,LocalDate tradeDate,LocalDate confirmDate,BigDecimal shares,BigDecimal grossAmount,BigDecimal fee,BigDecimal confirmedNav,String idempotencyKey,String note,String reversesTransactionId) {
    public TransactionCommand(String fundCode,TransactionType type,LocalDate tradeDate,LocalDate confirmDate,BigDecimal shares,BigDecimal grossAmount,BigDecimal fee,BigDecimal confirmedNav,String idempotencyKey,String note){
        this(fundCode,type,tradeDate,confirmDate,shares,grossAmount,fee,confirmedNav,idempotencyKey,note,null);
    }
}
