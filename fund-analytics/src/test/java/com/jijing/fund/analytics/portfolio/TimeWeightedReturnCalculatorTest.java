package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定时间加权收益率在缺失价值时返回空，以及正增长可以连乘。
 * 日期颠倒目前不会被拒绝；若实现改成抛出异常，对应断言会失败。
 */
class TimeWeightedReturnCalculatorTest {

    /**
     * 锁定期初价值缺失时返回空，而不是零收益。
     * 若得到数值零或其他收益率，断言失败。
     */
    @Test
    void returnsEmptyWhenBeginValueMissingInsteadOfZero() {
        var calculator = new TimeWeightedReturnCalculator();
        assertThat(calculator.calculate(List.of(new TimeWeightedReturnCalculator.SubPeriod(
                LocalDate.MIN, LocalDate.MAX, null, BigDecimal.TEN, BigDecimal.ZERO)))).isEmpty();
    }

    /**
     * 锁定单段 10% 增长，并确认重复计算相等。
     * 缩放或连乘结果变化时断言失败。
     */
    @Test
    void chainsPositiveGrowth() {
        var calculator = new TimeWeightedReturnCalculator();
        var periods = List.of(new TimeWeightedReturnCalculator.SubPeriod(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ZERO));
        var result = calculator.calculate(periods);
        assertThat(result).contains(new BigDecimal("0.10000000"));
        assertThat(calculator.calculate(periods)).isEqualTo(result);
    }

    /**
     * 锁定 null、空列表、期末缺失、期初不大于零、增长因子不大于零，以及后一段失败时整段为空。
     * 重复的空结果必须相等。这些输入若返回零，断言失败。
     */
    @Test
    void returnsEmptyForMissingOrNonPositivePeriods() {
        var calculator = new TimeWeightedReturnCalculator();
        var start = LocalDate.of(2026, 1, 1);
        var end = LocalDate.of(2026, 6, 1);
        assertThat(calculator.calculate(null)).isEmpty();
        assertThat(calculator.calculate(List.of())).isEmpty();
        assertThat(calculator.calculate(List.of())).isEqualTo(calculator.calculate(List.of()));
        assertThat(calculator.calculate(List.of(period(start, end, new BigDecimal("100"), null, BigDecimal.ZERO)))).isEmpty();
        assertThat(calculator.calculate(List.of(period(start, end, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO)))).isEmpty();
        assertThat(calculator.calculate(List.of(period(start, end, new BigDecimal("-1"), BigDecimal.TEN, BigDecimal.ZERO)))).isEmpty();
        assertThat(calculator.calculate(List.of(period(start, end, new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO)))).isEmpty();
        assertThat(calculator.calculate(List.of(period(start, end, new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("100"))))).isEmpty();
        var valid = period(start, end, new BigDecimal("100"), new BigDecimal("110"), BigDecimal.ZERO);
        var invalid = period(start, end, BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO);
        assertThat(calculator.calculate(List.of(valid, invalid))).isEmpty();
    }

    /**
     * 锁定缺失起止日期和 null 子区间会抛出空指针。
     * 止日早于起日、外部现金流为 null 时仍按金额计算，不视为非法区间。
     */
    @Test
    void rejectsNullDatesButIgnoresReversedRange() {
        var calculator = new TimeWeightedReturnCalculator();
        assertThatThrownBy(() -> new TimeWeightedReturnCalculator.SubPeriod(
                null, LocalDate.of(2026, 6, 1), BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeWeightedReturnCalculator.SubPeriod(
                LocalDate.of(2026, 1, 1), null, BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.calculate(Collections.singletonList(null))).isInstanceOf(NullPointerException.class);
        var reversed = new TimeWeightedReturnCalculator.SubPeriod(
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), new BigDecimal("100"), new BigDecimal("110"), null);
        assertThat(calculator.calculate(List.of(reversed))).contains(new BigDecimal("0.10000000"));
    }

    /**
     * 组装一段子区间。
     * 起止为 null 时构造即失败，调用方看不到收益率。
     */
    private static TimeWeightedReturnCalculator.SubPeriod period(LocalDate start, LocalDate end, BigDecimal begin,
            BigDecimal ending, BigDecimal flow) {
        return new TimeWeightedReturnCalculator.SubPeriod(start, end, begin, ending, flow);
    }
}
