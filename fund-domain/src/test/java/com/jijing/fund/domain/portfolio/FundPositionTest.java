package com.jijing.fund.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 验证 {@link FundPosition#averageCostPerShare} 在零份额、缺失数值和舍入时的行为。 */
class FundPositionTest {
    /** 用给定份额和剩余成本构造持仓，其余字段取 0 或 null。 */
    private static FundPosition position(BigDecimal shares, BigDecimal cost) {
        return new FundPosition(new FundCode("000001"), shares, cost, BigDecimal.ZERO, BigDecimal.ZERO, null);
    }

    /** 份额为 0（包括带小数位的 0）时返回 0，不会除零，也不会读取剩余成本。 */
    @Test
    void zeroSharesReturnZeroWithoutTouchingCost() {
        assertThat(position(BigDecimal.ZERO, new BigDecimal("100")).averageCostPerShare()).isEqualTo(BigDecimal.ZERO);
        assertThat(position(new BigDecimal("0.00"), null).averageCostPerShare()).isEqualTo(BigDecimal.ZERO);
    }

    /** 结果保留 8 位小数并按 HALF_UP 舍入。 */
    @Test
    void roundsHalfUpToEightDecimals() {
        assertThat(position(new BigDecimal("3"), new BigDecimal("2")).averageCostPerShare())
                .isEqualTo(new BigDecimal("0.66666667"));
    }

    /** 份额缺失，或份额非 0 而剩余成本缺失时抛出 NullPointerException。 */
    @Test
    void missingValuesFail() {
        assertThatThrownBy(() -> position(null, BigDecimal.ONE).averageCostPerShare()).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> position(BigDecimal.ONE, null).averageCostPerShare()).isInstanceOf(NullPointerException.class);
    }
}
