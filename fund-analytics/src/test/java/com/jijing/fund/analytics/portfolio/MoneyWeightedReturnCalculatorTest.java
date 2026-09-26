package com.jijing.fund.analytics.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定货币加权收益率与样例现金流一致，并覆盖无解、空样本和 null。
 * 有解时重复计算必须相等；无解时必须是空结果，不能是零。
 */
class MoneyWeightedReturnCalculatorTest {

    /**
     * 用已核对的样例现金流检查收益率接近 10%，并确认重复计算相等。
     * 样例资源缺失时读取会抛出空指针；数值偏离容差或两次结果不同时断言失败。
     */
    @Test
    void matchesCheckedExcelSampleCashFlows() throws Exception {
        try (var in = getClass().getResourceAsStream("/xirr-excel-sample.csv")) {
            var lines = new String(in.readAllBytes()).lines().toList();
            var flows = new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
            for (int i = 1; i < lines.size(); i++) {
                var parts = lines.get(i).split(",");
                flows.add(new MoneyWeightedReturnCalculator.CashFlow(LocalDate.parse(parts[0]), new BigDecimal(parts[1])));
            }
            var calculator = new MoneyWeightedReturnCalculator();
            var first = calculator.calculate(flows);
            assertThat(first).hasValueSatisfying(value -> assertThat(value).isCloseTo(new BigDecimal("0.10"), within(new BigDecimal("0.001"))));
            assertThat(calculator.calculate(flows)).isEqualTo(first);
        }
    }

    /**
     * 锁定同号现金流没有内部收益率。
     * 若返回了数值，断言失败。
     */
    @Test
    void returnsEmptyWhenCashFlowsCannotHaveIrr() {
        assertThat(new MoneyWeightedReturnCalculator().calculate(List.of(
                new MoneyWeightedReturnCalculator.CashFlow(LocalDate.now(), BigDecimal.ONE),
                new MoneyWeightedReturnCalculator.CashFlow(LocalDate.now().plusDays(1), BigDecimal.TEN)))).isEmpty();
    }

    /**
     * 锁定 null、空列表、单笔、全负、全零以及区间两端同号时都返回空，且空结果可以重复得到。
     * 这些输入若抛出异常或返回零，断言失败。
     */
    @Test
    void returnsEmptyForNullEmptyAndUnsolvableFlows() {
        var calculator = new MoneyWeightedReturnCalculator();
        var day = LocalDate.of(2026, 1, 1);
        assertThat(calculator.calculate(null)).isEmpty();
        assertThat(calculator.calculate(List.of())).isEmpty();
        assertThat(calculator.calculate(List.of())).isEqualTo(calculator.calculate(List.of()));
        assertThat(calculator.calculate(List.of(new MoneyWeightedReturnCalculator.CashFlow(day, BigDecimal.ONE)))).isEmpty();
        assertThat(calculator.calculate(List.of(
                new MoneyWeightedReturnCalculator.CashFlow(day, new BigDecimal("-1")),
                new MoneyWeightedReturnCalculator.CashFlow(day.plusDays(1), new BigDecimal("-2"))))).isEmpty();
        assertThat(calculator.calculate(List.of(
                new MoneyWeightedReturnCalculator.CashFlow(day, BigDecimal.ZERO),
                new MoneyWeightedReturnCalculator.CashFlow(day.plusDays(1), BigDecimal.ZERO)))).isEmpty();
        assertThat(calculator.calculate(List.of(
                new MoneyWeightedReturnCalculator.CashFlow(day, new BigDecimal("1000")),
                new MoneyWeightedReturnCalculator.CashFlow(day.plusDays(1), new BigDecimal("-1"))))).isEmpty();
        assertThat(calculator.calculate(List.of(
                new MoneyWeightedReturnCalculator.CashFlow(day, new BigDecimal("1E400")),
                new MoneyWeightedReturnCalculator.CashFlow(day.plusDays(1), new BigDecimal("-1E400"))))).isEmpty();
    }

    /**
     * 锁定缺失日期、缺失金额，以及列表中的 null 元素会抛出空指针。
     * 这些输入若被当成空收益率，断言失败。日期先后不构成非法区间，计算前会自行排序。
     */
    @Test
    void rejectsNullCashFlowParts() {
        var calculator = new MoneyWeightedReturnCalculator();
        assertThatThrownBy(() -> new MoneyWeightedReturnCalculator.CashFlow(null, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MoneyWeightedReturnCalculator.CashFlow(LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(NullPointerException.class);
        var flows = new ArrayList<MoneyWeightedReturnCalculator.CashFlow>();
        flows.add(new MoneyWeightedReturnCalculator.CashFlow(LocalDate.of(2026, 1, 1), BigDecimal.ONE));
        flows.add(null);
        assertThatThrownBy(() -> calculator.calculate(flows)).isInstanceOf(NullPointerException.class);
    }
}
