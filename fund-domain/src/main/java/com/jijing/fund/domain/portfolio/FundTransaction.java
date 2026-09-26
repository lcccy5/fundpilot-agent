package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.*;

/**
 * 组合中的一笔基金交易流水（申购、赎回、分红、转换、冲正等），只追加不修改；
 * 通过幂等键防止重复提交，冲正交易通过 reversesTransactionId 指向被冲正的原交易。
 */
public record FundTransaction(String transactionId, PortfolioId portfolioId, UserId ownerUserId, FundCode fundCode,
                              TransactionType transactionType, LocalDate tradeDate, LocalDate confirmDate,
                              BigDecimal shares, BigDecimal grossAmount, BigDecimal fee, BigDecimal confirmedNav,
                              String currency, String source, String idempotencyKey, String reversesTransactionId,
                              Instant createdAt) {
    /**
     * 校验交易必填项并规范化金额。
     * transactionId 或 idempotencyKey 为 null/空白、tradeDate、confirmDate 或 transactionType 为 null 时抛出
     * IllegalArgumentException（"transaction is invalid"）；份额、金额、费用为 null 时按 0 处理，任一为负数时抛出
     * IllegalArgumentException。组合、用户、基金代码、确认净值等其余字段不校验，确认日期早于交易日期也不会被拒绝。
     */
    public FundTransaction {
        if (transactionId == null || transactionId.isBlank()
                || tradeDate == null
                || confirmDate == null
                || transactionType == null
                || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("transaction is invalid");
        }
        shares = zero(shares);
        grossAmount = zero(grossAmount);
        fee = zero(fee);
        if (shares.signum() < 0 || grossAmount.signum() < 0 || fee.signum() < 0) {
            throw new IllegalArgumentException("transaction amounts cannot be negative");
        }
    }

    /** 把缺省（null）的数量类字段替换为 0，非 null 值原样返回，不会抛出异常。 */
    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
