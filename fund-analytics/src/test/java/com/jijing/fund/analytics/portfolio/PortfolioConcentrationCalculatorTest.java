package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定集中度在没有正市值时不可用，以及同一输入重复计算相等。
 * 这里没有日期区间；非正市值会被丢掉，而不是当成非法参数抛出。
 */
class PortfolioConcentrationCalculatorTest {

    /**
     * 锁定 null、空列表、全是 null、零和负数都得到不可用结果，且权重字段为 null。
     * 重复计算空列表必须相等。若返回零权重，断言失败。
     */
    @Test
    void returnsUnavailableForNullEmptyAndNonPositiveValues() {
        var calculator = new PortfolioConcentrationCalculator();
        assertThat(calculator.calculate(null).status()).isEqualTo("UNAVAILABLE");
        assertThat(calculator.calculate(null).maxFundWeight()).isNull();
        assertThat(calculator.calculate(null).top3Weight()).isNull();
        assertThat(calculator.calculate(null).hhi()).isNull();
        assertThat(calculator.calculate(List.of()).status()).isEqualTo("UNAVAILABLE");
        assertThat(calculator.calculate(List.of())).isEqualTo(calculator.calculate(List.of()));
        assertThat(calculator.calculate(Arrays.asList(null, null)).status()).isEqualTo("UNAVAILABLE");
        assertThat(calculator.calculate(List.of(BigDecimal.ZERO, new BigDecimal("-2"))).status()).isEqualTo("UNAVAILABLE");
    }

    /**
     * 锁定正市值中夹着 null 时仍可计算，并且重复调用结果相等。
     * 没有正市值时不应走到这条路径。
     */
    @Test
    void ignoresNullElementsAndRepeatsPositiveResult() {
        var calculator = new PortfolioConcentrationCalculator();
        var values = Arrays.asList(null, new BigDecimal("1"), new BigDecimal("3"));
        var first = calculator.calculate(values);
        var second = calculator.calculate(values);
        assertThat(first.status()).isEqualTo("AVAILABLE");
        assertThat(first.maxFundWeight()).isEqualByComparingTo("0.75000000");
        assertThat(first.top3Weight()).isEqualByComparingTo("1.00000000");
        assertThat(first.hhi()).isEqualByComparingTo("0.62500000");
        assertThat(second).isEqualTo(first);
    }
}
