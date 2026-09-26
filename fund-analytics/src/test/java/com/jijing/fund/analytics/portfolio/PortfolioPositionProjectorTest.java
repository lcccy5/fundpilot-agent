package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定持仓重放的成本结转，以及空流水、null 和非法份额时的失败方式。
 * 断言失败说明投影结果或异常类型偏离了当前规则。负金额在进入投影前就会被流水构造拒绝。
 */
class PortfolioPositionProjectorTest {

    /**
     * 锁定申购后再赎回时的移动平均成本和已实现收益。
     * 同一批流水重复投影必须相等；份额或成本被改写成零时断言失败。
     */
    @Test
    void replaysSubscriptionAndRedemptionWithMovingAverageCost() {
        var portfolioId = new PortfolioId("00000000-0000-0000-0000-000000000100");
        var userId = new UserId("00000000-0000-0000-0000-000000000200");
        var fundCode = new FundCode("000001");
        var projector = new PortfolioPositionProjector();
        var rows = List.of(
                transaction("a", portfolioId, userId, fundCode, TransactionType.SUBSCRIPTION, "10", "100", "1", LocalDate.of(2026, 1, 2)),
                transaction("b", portfolioId, userId, fundCode, TransactionType.REDEMPTION, "4", "50", "1", LocalDate.of(2026, 1, 3)));
        var result = projector.project(rows).getFirst();
        assertThat(result.confirmedShares()).isEqualByComparingTo("6");
        assertThat(result.remainingCost()).isEqualByComparingTo("60.6000");
        assertThat(result.realizedProfit()).isEqualByComparingTo("8.6000");
        assertThat(projector.project(rows)).isEqualTo(projector.project(rows));
    }

    /**
     * 锁定等额赎回可以抵消先前申购。
     * 份额或剩余成本没有回到零时断言失败。
     */
    @Test
    void compensatingRedemptionUndoesSubscription() {
        var portfolioId = new PortfolioId("00000000-0000-0000-0000-000000000102");
        var userId = new UserId("00000000-0000-0000-0000-000000000202");
        var fundCode = new FundCode("000001");
        var projector = new PortfolioPositionProjector();
        var buy = transaction("a", portfolioId, userId, fundCode, TransactionType.SUBSCRIPTION, "10", "100", "0", LocalDate.of(2026, 1, 2));
        var undo = transaction("b", portfolioId, userId, fundCode, TransactionType.REDEMPTION, "10", "100", "0", LocalDate.of(2026, 1, 3));
        var after = projector.project(List.of(buy, undo)).getFirst();
        assertThat(after.confirmedShares()).isEqualByComparingTo("0");
        assertThat(after.remainingCost()).isEqualByComparingTo("0.0000");
    }

    /**
     * 锁定没有持仓时的赎回会被拒绝。
     * 未抛出 {@link IllegalArgumentException} 时断言失败。
     */
    @Test
    void rejectsRedemptionBeyondConfirmedShares() {
        var portfolioId = new PortfolioId("00000000-0000-0000-0000-000000000101");
        var userId = new UserId("00000000-0000-0000-0000-000000000201");
        var fundCode = new FundCode("000001");
        assertThatThrownBy(() -> new PortfolioPositionProjector().project(List.of(
                transaction("a", portfolioId, userId, fundCode, TransactionType.REDEMPTION, "1", "1", "0", LocalDate.now()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 锁定 null 流水抛出空指针，空流水得到空列表且重复投影仍为空。
     * 空列表被当成异常，或 null 被当成空持仓时断言失败。
     */
    @Test
    void rejectsNullTransactionsAndRepeatedEmptyProjection() {
        var projector = new PortfolioPositionProjector();
        assertThatThrownBy(() -> projector.project(null)).isInstanceOf(NullPointerException.class);
        assertThat(projector.project(List.of())).isEmpty();
        assertThat(projector.project(List.of())).isEqualTo(projector.project(List.of()));
    }

    /**
     * 锁定列表中的 null 元素、零份额买入和未拆分的冲正都会失败。
     * 这些输入若产生持仓，断言失败。冲正必须在投影前变成补偿流水。
     */
    @Test
    void rejectsNullElementNonPositivePurchaseAndReversal() {
        var portfolioId = new PortfolioId("00000000-0000-0000-0000-000000000103");
        var userId = new UserId("00000000-0000-0000-0000-000000000203");
        var fundCode = new FundCode("000001");
        var projector = new PortfolioPositionProjector();
        assertThatThrownBy(() -> projector.project(Collections.singletonList(null))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> projector.project(List.of(
                transaction("a", portfolioId, userId, fundCode, TransactionType.SUBSCRIPTION, "0", "100", "0", LocalDate.of(2026, 1, 2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("purchase shares must be positive");
        assertThatThrownBy(() -> projector.project(List.of(
                transaction("b", portfolioId, userId, fundCode, TransactionType.REVERSAL, "1", "1", "0", LocalDate.of(2026, 1, 3)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REVERSAL must be resolved into a compensating transaction before projection");
    }

    /**
     * 组装一条测试流水。
     * 份额、金额或费用为负数时，流水构造会先抛出 {@link IllegalArgumentException}，投影代码不会执行。
     */
    private static FundTransaction transaction(String id, PortfolioId portfolioId, UserId userId, FundCode fundCode,
            TransactionType type, String shares, String amount, String fee, LocalDate date) {
        return new FundTransaction(id, portfolioId, userId, fundCode, type, date, date, new BigDecimal(shares),
                new BigDecimal(amount), new BigDecimal(fee), null, "CNY", "TEST", id, null, Instant.EPOCH);
    }
}
