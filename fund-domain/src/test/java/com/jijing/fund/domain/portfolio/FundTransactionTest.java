package com.jijing.fund.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 验证 {@link FundTransaction} 的必填项校验、金额缺省为 0 以及负数金额的拒绝。 */
class FundTransactionTest {
    private static final LocalDate TRADE = LocalDate.of(2026, 9, 1);
    private static final LocalDate CONFIRM = LocalDate.of(2026, 9, 2);

    /** 用给定的标识、类型、日期和幂等键构造一笔金额为空的交易。 */
    private static FundTransaction transaction(String id, TransactionType type, LocalDate trade, LocalDate confirm, String key) {
        return transaction(id, type, trade, confirm, key, null, null, null);
    }

    /** 用给定的全部关键字段构造交易，其余字段取合法默认值。 */
    private static FundTransaction transaction(String id, TransactionType type, LocalDate trade, LocalDate confirm, String key,
                                               BigDecimal shares, BigDecimal gross, BigDecimal fee) {
        return new FundTransaction(id, PortfolioId.random(), UserId.random(), new FundCode("000001"), type, trade, confirm,
                shares, gross, fee, null, "CNY", "manual", key, null, Instant.EPOCH);
    }

    /** 交易标识为 null 或空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingTransactionId() {
        assertThatThrownBy(() -> transaction(null, TransactionType.SUBSCRIPTION, TRADE, CONFIRM, "k1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
        assertThatThrownBy(() -> transaction(" ", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, "k1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
    }

    /** 交易类型、交易日期或确认日期缺失时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingTypeOrDates() {
        assertThatThrownBy(() -> transaction("t1", null, TRADE, CONFIRM, "k1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, null, CONFIRM, "k1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, null, "k1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
    }

    /** 幂等键为 null 或空白时抛出 IllegalArgumentException，防止无法去重的交易入库。 */
    @Test
    void rejectsMissingIdempotencyKey() {
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, ""))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("transaction is invalid");
    }

    /** 份额、金额、费用缺失时按 0 处理，而不是保留 null。 */
    @Test
    void missingAmountsDefaultToZero() {
        var transaction = transaction("t1", TransactionType.CASH_DIVIDEND, TRADE, CONFIRM, "k1");

        assertThat(transaction.shares()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(transaction.grossAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(transaction.fee()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** 份额、金额或费用任一为负数时抛出 IllegalArgumentException。 */
    @Test
    void rejectsNegativeAmounts() {
        BigDecimal negative = new BigDecimal("-0.01");

        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, "k1", negative, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, "k1", null, negative, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
        assertThatThrownBy(() -> transaction("t1", TransactionType.SUBSCRIPTION, TRADE, CONFIRM, "k1", null, null, negative))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("negative");
    }
}
