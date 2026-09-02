package com.jijing.fund.analytics.calculator;

import com.jijing.fund.analytics.model.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 纯函数指标引擎。百分比在内部使用小数，0.12 表示 12%。 */
public final class FundMetricsCalculator {
    private static final int SCALE = 8;
    private static final MathContext MC = MathContext.DECIMAL128;

    public FundMetrics calculate(NavSeries series, CalculationContext context) {
        List<NavObservation> observations = series.observations();
        LocalDate actualStart = observations.isEmpty() ? null : observations.get(0).date();
        LocalDate actualEnd = observations.isEmpty() ? null : observations.get(observations.size() - 1).date();
        if (observations.size() < 2) return insufficient(series, context, actualStart, actualEnd);
        List<BigDecimal> returns = dailyReturns(observations);
        MetricValue cumulative = available(ratio(observations.getLast().nav(), observations.getFirst().nav()));
        MetricValue annualized = annualized(observations, context);
        boolean lowCoverage = series.coverage().status() == CoverageStatus.INSUFFICIENT;
        MetricValue volatility = lowCoverage ? unavailable("LOW_DATA_COVERAGE") : volatility(returns, context.minVolatilityReturns(), context.tradingDaysPerYear());
        DrawdownResult drawdown = drawdown(observations);
        MetricValue sharpe = lowCoverage ? unavailable("LOW_DATA_COVERAGE") : sharpe(returns, context);
        MetricValue positive = available(BigDecimal.valueOf(returns.stream().filter(v -> v.signum() > 0).count())
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP));
        MetricValue best = available(returns.stream().max(BigDecimal::compareTo).orElseThrow());
        MetricValue worst = available(returns.stream().min(BigDecimal::compareTo).orElseThrow());
        return new FundMetrics(series.fundCode(), series.requestedStartDate(), series.requestedEndDate(), actualStart,
                actualEnd, series.navBasis(), observations.size(), series.coverage(), cumulative, annualized,
                volatility, available(drawdown.value()), drawdown.period(), sharpe, positive, best, worst,
                context.annualRiskFreeRate(), context.algorithmVersion(), series.dataVersion(), context.clock().instant());
    }

    private FundMetrics insufficient(NavSeries s, CalculationContext c, LocalDate start, LocalDate end) {
        MetricValue unavailable = unavailable("INSUFFICIENT_OBSERVATIONS");
        return new FundMetrics(s.fundCode(), s.requestedStartDate(), s.requestedEndDate(), start, end, s.navBasis(),
                s.observations().size(), s.coverage(), unavailable, unavailable, unavailable, unavailable, null,
                unavailable, unavailable, unavailable, unavailable, c.annualRiskFreeRate(), c.algorithmVersion(),
                s.dataVersion(), c.clock().instant());
    }

    private List<BigDecimal> dailyReturns(List<NavObservation> observations) {
        var result = new ArrayList<BigDecimal>(observations.size() - 1);
        for (int i = 1; i < observations.size(); i++) result.add(ratio(observations.get(i).nav(), observations.get(i - 1).nav()));
        return result;
    }

    private MetricValue annualized(List<NavObservation> observations, CalculationContext context) {
        long days = ChronoUnit.DAYS.between(observations.getFirst().date(), observations.getLast().date());
        if (days < context.minAnnualizationDays()) return unavailable("PERIOD_TOO_SHORT");
        double base = observations.getLast().nav().divide(observations.getFirst().nav(), MC).doubleValue();
        double value = StrictMath.pow(base, 365.2425d / days) - 1d;
        return finite(value);
    }

    private MetricValue volatility(List<BigDecimal> returns, int minimum, int tradingDays) {
        if (returns.size() < minimum) return unavailable("INSUFFICIENT_OBSERVATIONS");
        double std = sampleStdDev(returns); return finite(std * StrictMath.sqrt(tradingDays));
    }

    private MetricValue sharpe(List<BigDecimal> returns, CalculationContext context) {
        if (returns.size() < context.minSharpeReturns()) return unavailable("INSUFFICIENT_OBSERVATIONS");
        double std = sampleStdDev(returns); if (std == 0d) return unavailable("ZERO_VOLATILITY");
        double dailyRiskFree = StrictMath.pow(1d + context.annualRiskFreeRate().doubleValue(), 1d / context.tradingDaysPerYear()) - 1d;
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        return finite((mean - dailyRiskFree) / std * StrictMath.sqrt(context.tradingDaysPerYear()));
    }

    private DrawdownResult drawdown(List<NavObservation> observations) {
        NavObservation peak = observations.getFirst(), bestPeak = peak, trough = peak;
        BigDecimal worst = BigDecimal.ZERO;
        for (NavObservation item : observations) {
            if (item.nav().compareTo(peak.nav()) > 0) peak = item;
            BigDecimal value = ratio(item.nav(), peak.nav());
            if (value.compareTo(worst) < 0) { worst = value; bestPeak = peak; trough = item; }
        }
        LocalDate recovery = null;
        if (worst.signum() < 0) {
            for (NavObservation item : observations) {
                if (item.date().isAfter(trough.date()) && item.nav().compareTo(bestPeak.nav()) >= 0) { recovery = item.date(); break; }
            }
        }
        return new DrawdownResult(scale(worst), new DrawdownPeriod(bestPeak.date(), trough.date(), recovery));
    }

    private double sampleStdDev(List<BigDecimal> values) {
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        double sum = values.stream().mapToDouble(v -> { double delta = v.doubleValue() - mean; return delta * delta; }).sum();
        return StrictMath.sqrt(sum / (values.size() - 1));
    }
    private BigDecimal ratio(BigDecimal current, BigDecimal previous) { return scale(current.divide(previous, MC).subtract(BigDecimal.ONE)); }
    private MetricValue available(BigDecimal value) { return MetricValue.available(scale(value)); }
    private MetricValue unavailable(String reason) { return MetricValue.unavailable(reason); }
    private MetricValue finite(double value) { return Double.isFinite(value) ? available(BigDecimal.valueOf(value)) : unavailable("INVALID_NUMERIC_RESULT"); }
    private BigDecimal scale(BigDecimal value) { return value.setScale(SCALE, RoundingMode.HALF_UP); }
    private record DrawdownResult(BigDecimal value, DrawdownPeriod period) {}
}
