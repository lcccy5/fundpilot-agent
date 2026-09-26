package com.jijing.fund.analytics.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.model.DataCoverage;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricStatus;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.analytics.model.NavObservation;
import com.jijing.fund.analytics.model.NavSeries;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定收益、回撤和风险指标在样本不足、零波动和低覆盖时的原因码。
 * 非法日期区间由净值序列拒绝，到不了这里；null 序列、null 上下文和 null 时钟在计算入口失败。
 */
class FundMetricsCalculatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    private final FundMetricsCalculator calculator = new FundMetricsCalculator();

    /**
     * 锁定累计收益、最大回撤、收复日和上涨日占比。
     * 同一序列在固定时钟下重复计算必须相等。回撤区间被算错时断言失败。
     */
    @Test
    void calculatesReturnDrawdownAndRecovery() {
        var input = series("1.00", "1.20", "0.90", "1.20");
        var context = context(1, 1, 1);
        FundMetrics metrics = calculator.calculate(input, context);

        assertThat(metrics.cumulativeReturn().value()).isEqualByComparingTo("0.20000000");
        assertThat(metrics.maxDrawdown().value()).isEqualByComparingTo("-0.25000000");
        assertThat(metrics.maxDrawdownPeriod().peakDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(metrics.maxDrawdownPeriod().troughDate()).isEqualTo(LocalDate.of(2026, 1, 3));
        assertThat(metrics.maxDrawdownPeriod().recoveryDate()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(metrics.positiveDayRatio().value()).isEqualByComparingTo("0.66666667");
        assertThat(calculator.calculate(input, context)).isEqualTo(metrics);
    }

    /**
     * 锁定只有一个观测时全部指标不可用，且没有回撤区间。
     * 若仍给出收益率或用零填充回撤，断言失败。
     */
    @Test
    void marksMetricsUnavailableWhenThereIsOnlyOneObservation() {
        FundMetrics metrics = calculator.calculate(series("1.00"), context(1, 1, 1));

        assertThat(metrics.cumulativeReturn().status()).isEqualTo(MetricStatus.UNAVAILABLE);
        assertThat(metrics.cumulativeReturn().unavailableReason()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(metrics.maxDrawdownPeriod()).isNull();
    }

    /**
     * 锁定净值不变时夏普因零波动不可用，年化波动则为零。
     * 若夏普被算成零或无穷，断言失败。
     */
    @Test
    void protectsSharpeAgainstZeroVolatility() {
        FundMetrics metrics = calculator.calculate(series("1.00", "1.00", "1.00"), context(1, 1, 1));

        assertThat(metrics.sharpeRatio().status()).isEqualTo(MetricStatus.UNAVAILABLE);
        assertThat(metrics.sharpeRatio().unavailableReason()).isEqualTo("ZERO_VOLATILITY");
        assertThat(metrics.annualizedVolatility().value()).isEqualByComparingTo("0.00000000");
    }

    /**
     * 锁定交易日覆盖不足时，波动率和夏普都不可用。
     * 收益指标不在这条路径上被一并清空。
     */
    @Test
    void blocksRiskMetricsWhenTradingDayCoverageIsTooLow() {
        NavSeries input = new NavSeries(new FundCode("000001"), NavBasis.UNIT_NAV,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                List.of(new NavObservation(LocalDate.of(2026, 1, 1), new BigDecimal("1.00")),
                        new NavObservation(LocalDate.of(2026, 1, 2), new BigDecimal("1.01"))),
                DataCoverage.of(2, 10), "1");

        FundMetrics metrics = calculator.calculate(input, context(1, 1, 1));

        assertThat(metrics.annualizedVolatility().unavailableReason()).isEqualTo("LOW_DATA_COVERAGE");
        assertThat(metrics.sharpeRatio().unavailableReason()).isEqualTo("LOW_DATA_COVERAGE");
    }

    /**
     * 锁定空序列得到样本不足，并且重复计算相等。
     * null 序列、null 上下文或 null 时钟必须抛出空指针，不能返回空指标对象。
     */
    @Test
    void rejectsEmptySeriesAndNullInputs() {
        NavSeries empty = new NavSeries(new FundCode("000001"), NavBasis.UNIT_NAV,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                List.of(), DataCoverage.of(0, 0), "1");
        FundMetrics first = calculator.calculate(empty, context(1, 1, 1));
        assertThat(first.cumulativeReturn().unavailableReason()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(first.actualStartDate()).isNull();
        assertThat(first.maxDrawdownPeriod()).isNull();
        assertThat(calculator.calculate(empty, context(1, 1, 1))).isEqualTo(first);
        assertThatThrownBy(() -> calculator.calculate(null, context(1, 1, 1))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> calculator.calculate(series("1.00", "1.10"), null)).isInstanceOf(NullPointerException.class);
        var missingClock = new CalculationContext(BigDecimal.ZERO, 252, 1, 1, 1, "test-v1", null);
        assertThatThrownBy(() -> calculator.calculate(series("1.00", "1.10"), missingClock)).isInstanceOf(NullPointerException.class);
    }

    /**
     * 锁定区间过短、收益样本不足，以及单个收益无法计算样本标准差时的原因码。
     * 累计收益在这些情况下仍然可用。非法请求区间到不了计算入口。
     */
    @Test
    void marksShortPeriodAndInvalidDeviationUnavailable() {
        FundMetrics shortPeriod = calculator.calculate(series("1.00", "1.10"), context(20, 60, 365));
        assertThat(shortPeriod.cumulativeReturn().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(shortPeriod.annualizedReturn().unavailableReason()).isEqualTo("PERIOD_TOO_SHORT");
        assertThat(shortPeriod.annualizedVolatility().unavailableReason()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(shortPeriod.sharpeRatio().unavailableReason()).isEqualTo("INSUFFICIENT_OBSERVATIONS");

        FundMetrics undefinedDeviation = calculator.calculate(series("1.00", "1.10"), context(1, 100, 1000));
        assertThat(undefinedDeviation.annualizedVolatility().unavailableReason()).isEqualTo("INVALID_NUMERIC_RESULT");
    }

    /**
     * 锁定负的交易日数使年化波动成为无效数值，零个交易日则得到有限的零波动。
     * 前者是非法参数的失败结果；后者目前不抛出异常。
     */
    @Test
    void handlesNonPositiveTradingDayCounts() {
        var negativeDays = new CalculationContext(BigDecimal.ZERO, -1, 1, 100, 1000, "test-v1", CLOCK);
        FundMetrics invalid = calculator.calculate(series("1.00", "1.20", "1.10"), negativeDays);
        assertThat(invalid.annualizedVolatility().unavailableReason()).isEqualTo("INVALID_NUMERIC_RESULT");

        var zeroDays = new CalculationContext(BigDecimal.ZERO, 0, 1, 100, 1000, "test-v1", CLOCK);
        FundMetrics zeroVolatility = calculator.calculate(series("1.00", "1.20", "1.10"), zeroDays);
        assertThat(zeroVolatility.annualizedVolatility().status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(zeroVolatility.annualizedVolatility().value()).isEqualByComparingTo("0.00000000");
    }

    /**
     * 按顺序生成落在请求区间内的净值序列。
     * 净值非正或日期重复时，序列构造会先失败。
     */
    private NavSeries series(String... navs) {
        var observations = java.util.stream.IntStream.range(0, navs.length)
                .mapToObj(i -> new NavObservation(LocalDate.of(2026, 1, 1).plusDays(i), new BigDecimal(navs[i])))
                .toList();
        return new NavSeries(new FundCode("000001"), NavBasis.UNIT_NAV,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, Math.max(1, navs.length)),
                observations, DataCoverage.of(navs.length, navs.length), "1");
    }

    /**
     * 组装测试用计算上下文。
     * 时钟固定，因此重复计算的时间戳相同；交易日数和最小样本若为负，失败会推迟到公式内部。
     */
    private CalculationContext context(int volatilityMinimum, int sharpeMinimum, int annualizationDays) {
        return new CalculationContext(BigDecimal.ZERO, 252, volatilityMinimum, sharpeMinimum,
                annualizationDays, "test-v1", CLOCK);
    }
}
