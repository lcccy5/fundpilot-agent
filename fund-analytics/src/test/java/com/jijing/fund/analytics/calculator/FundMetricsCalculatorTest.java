package com.jijing.fund.analytics.calculator;

import com.jijing.fund.analytics.model.*;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FundMetricsCalculatorTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    private final FundMetricsCalculator calculator = new FundMetricsCalculator();

    @Test
    void calculatesReturnDrawdownAndRecovery() {
        FundMetrics metrics = calculator.calculate(series("1.00", "1.20", "0.90", "1.20"), context(1, 1, 1));

        assertThat(metrics.cumulativeReturn().value()).isEqualByComparingTo("0.20000000");
        assertThat(metrics.maxDrawdown().value()).isEqualByComparingTo("-0.25000000");
        assertThat(metrics.maxDrawdownPeriod().peakDate()).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(metrics.maxDrawdownPeriod().troughDate()).isEqualTo(LocalDate.of(2026, 1, 3));
        assertThat(metrics.maxDrawdownPeriod().recoveryDate()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(metrics.positiveDayRatio().value()).isEqualByComparingTo("0.66666667");
    }

    @Test
    void marksMetricsUnavailableWhenThereIsOnlyOneObservation() {
        FundMetrics metrics = calculator.calculate(series("1.00"), context(1, 1, 1));

        assertThat(metrics.cumulativeReturn().status()).isEqualTo(MetricStatus.UNAVAILABLE);
        assertThat(metrics.cumulativeReturn().unavailableReason()).isEqualTo("INSUFFICIENT_OBSERVATIONS");
        assertThat(metrics.maxDrawdownPeriod()).isNull();
    }

    @Test
    void protectsSharpeAgainstZeroVolatility() {
        FundMetrics metrics = calculator.calculate(series("1.00", "1.00", "1.00"), context(1, 1, 1));

        assertThat(metrics.sharpeRatio().status()).isEqualTo(MetricStatus.UNAVAILABLE);
        assertThat(metrics.sharpeRatio().unavailableReason()).isEqualTo("ZERO_VOLATILITY");
        assertThat(metrics.annualizedVolatility().value()).isEqualByComparingTo("0.00000000");
    }

    @Test
    void blocksRiskMetricsWhenTradingDayCoverageIsTooLow() {
        NavSeries series = new NavSeries(new FundCode("000001"), NavBasis.UNIT_NAV,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
                List.of(new NavObservation(LocalDate.of(2026, 1, 1), new BigDecimal("1.00")),
                        new NavObservation(LocalDate.of(2026, 1, 2), new BigDecimal("1.01"))),
                DataCoverage.of(2, 10), "1");

        FundMetrics metrics = calculator.calculate(series, context(1, 1, 1));

        assertThat(metrics.annualizedVolatility().unavailableReason()).isEqualTo("LOW_DATA_COVERAGE");
        assertThat(metrics.sharpeRatio().unavailableReason()).isEqualTo("LOW_DATA_COVERAGE");
    }

    private NavSeries series(String... navs) {
        var observations = java.util.stream.IntStream.range(0, navs.length)
                .mapToObj(i -> new NavObservation(LocalDate.of(2026, 1, 1).plusDays(i), new BigDecimal(navs[i])))
                .toList();
        return new NavSeries(new FundCode("000001"), NavBasis.UNIT_NAV,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, Math.max(1, navs.length)),
                observations, DataCoverage.of(navs.length, navs.length), "1");
    }

    private CalculationContext context(int volatilityMinimum, int sharpeMinimum, int annualizationDays) {
        return new CalculationContext(BigDecimal.ZERO, 252, volatilityMinimum, sharpeMinimum,
                annualizationDays, "test-v1", CLOCK);
    }
}
