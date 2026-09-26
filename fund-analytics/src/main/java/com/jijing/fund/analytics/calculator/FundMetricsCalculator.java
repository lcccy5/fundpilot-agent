package com.jijing.fund.analytics.calculator;

import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.model.CoverageStatus;
import com.jijing.fund.analytics.model.DrawdownPeriod;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.analytics.model.NavObservation;
import com.jijing.fund.analytics.model.NavSeries;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 根据净值序列和计算上下文产生收益、波动、回撤和夏普。
 * 百分比在内部用小数，0.12 表示 12%。观测少于两个时全部指标不可用。序列或上下文为 null 时抛出 {@link NullPointerException}。
 * 低覆盖只阻断波动率和夏普。同一序列和固定时钟重复计算得到相等结果。
 */
public final class FundMetricsCalculator {
    private static final int SCALE = 8;
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * 计算区间收益、年化、波动、最大回撤、夏普以及日收益的胜率和极值。
     * 观测为空或只有一个时返回样本不足，回撤区间为 null。覆盖不足时波动率和夏普不可用，其余指标仍按样本计算。
     * 序列或上下文为 null、上下文时钟为 null 时抛出 {@link NullPointerException}。年化交易日数为负或样本标准差无法计算时，相关指标原因码为无效数值。
     * 相同输入和固定时钟重复调用结果相等。
     */
    public FundMetrics calculate(NavSeries series, CalculationContext context) {
        List<NavObservation> observations = series.observations();
        LocalDate actualStart = observations.isEmpty() ? null : observations.get(0).date();
        LocalDate actualEnd = observations.isEmpty() ? null : observations.get(observations.size() - 1).date();
        if (observations.size() < 2) {
            return insufficient(series, context, actualStart, actualEnd);
        }
        List<BigDecimal> returns = dailyReturns(observations);
        MetricValue cumulative = available(ratio(observations.getLast().nav(), observations.getFirst().nav()));
        MetricValue annualized = annualized(observations, context);
        boolean lowCoverage = series.coverage().status() == CoverageStatus.INSUFFICIENT;
        MetricValue volatility = lowCoverage
                ? unavailable("LOW_DATA_COVERAGE")
                : volatility(returns, context.minVolatilityReturns(), context.tradingDaysPerYear());
        DrawdownResult drawdown = drawdown(observations);
        MetricValue sharpe = lowCoverage ? unavailable("LOW_DATA_COVERAGE") : sharpe(returns, context);
        MetricValue positive = available(BigDecimal.valueOf(returns.stream().filter(value -> value.signum() > 0).count())
                .divide(BigDecimal.valueOf(returns.size()), SCALE, RoundingMode.HALF_UP));
        MetricValue best = available(returns.stream().max(BigDecimal::compareTo).orElseThrow());
        MetricValue worst = available(returns.stream().min(BigDecimal::compareTo).orElseThrow());
        return new FundMetrics(series.fundCode(), series.requestedStartDate(), series.requestedEndDate(), actualStart,
                actualEnd, series.navBasis(), observations.size(), series.coverage(), cumulative, annualized,
                volatility, available(drawdown.value()), drawdown.period(), sharpe, positive, best, worst,
                context.annualRiskFreeRate(), context.algorithmVersion(), series.dataVersion(), context.clock().instant());
    }

    /**
     * 组装观测不足时的整份指标，各项原因码都是样本不足。
     * 实际起止日允许为 null。回撤区间固定为 null，避免用零长度区间冒充没有回撤。时钟为 null 时在读取计算时刻处抛出 {@link NullPointerException}。
     */
    private FundMetrics insufficient(NavSeries series, CalculationContext context, LocalDate start, LocalDate end) {
        MetricValue unavailable = unavailable("INSUFFICIENT_OBSERVATIONS");
        return new FundMetrics(series.fundCode(), series.requestedStartDate(), series.requestedEndDate(), start, end, series.navBasis(),
                series.observations().size(), series.coverage(), unavailable, unavailable, unavailable, unavailable, null,
                unavailable, unavailable, unavailable, unavailable, context.annualRiskFreeRate(), context.algorithmVersion(),
                series.dataVersion(), context.clock().instant());
    }

    /**
     * 计算相邻净值的简单收益率。
     * 调用前观测至少有两个且净值都为正，因此这里不处理空序列，也不会因为前值为零而除零。结果长度比观测少一。
     */
    private List<BigDecimal> dailyReturns(List<NavObservation> observations) {
        var result = new ArrayList<BigDecimal>(observations.size() - 1);
        for (int i = 1; i < observations.size(); i++) {
            result.add(ratio(observations.get(i).nav(), observations.get(i - 1).nav()));
        }
        return result;
    }

    /**
     * 把首尾净值按实际日历年数换算成年化收益。
     * 首尾相隔天数小于上下文要求时返回区间过短，不外推。幂运算得到非有限值时原因码为无效数值。天数来自已排序的不同日期，正常序列不会是负数。
     */
    private MetricValue annualized(List<NavObservation> observations, CalculationContext context) {
        long days = ChronoUnit.DAYS.between(observations.getFirst().date(), observations.getLast().date());
        if (days < context.minAnnualizationDays()) {
            return unavailable("PERIOD_TOO_SHORT");
        }
        double base = observations.getLast().nav().divide(observations.getFirst().nav(), MC).doubleValue();
        double value = StrictMath.pow(base, 365.2425d / days) - 1d;
        return finite(value);
    }

    /**
     * 用样本标准差乘以交易日数的平方根，得到年化波动。
     * 收益个数小于最小样本时返回样本不足。交易日数为负或只有一个收益导致标准差非数值时，返回无效数值。交易日数为零时结果是有限的零，不视为失败。
     */
    private MetricValue volatility(List<BigDecimal> returns, int minimum, int tradingDays) {
        if (returns.size() < minimum) {
            return unavailable("INSUFFICIENT_OBSERVATIONS");
        }
        double std = sampleStdDev(returns);
        return finite(std * StrictMath.sqrt(tradingDays));
    }

    /**
     * 用日超额收益除以样本标准差，再按交易日数年化。
     * 收益个数不足时返回样本不足。标准差恰好为零时返回零波动，避免除零。无风险利率或交易日数使结果非有限时返回无效数值。
     */
    private MetricValue sharpe(List<BigDecimal> returns, CalculationContext context) {
        if (returns.size() < context.minSharpeReturns()) {
            return unavailable("INSUFFICIENT_OBSERVATIONS");
        }
        double std = sampleStdDev(returns);
        if (std == 0d) {
            return unavailable("ZERO_VOLATILITY");
        }
        double dailyRiskFree = StrictMath.pow(1d + context.annualRiskFreeRate().doubleValue(), 1d / context.tradingDaysPerYear()) - 1d;
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        return finite((mean - dailyRiskFree) / std * StrictMath.sqrt(context.tradingDaysPerYear()));
    }

    /**
     * 扫描净值，找出最深回撤及其收复日。
     * 净值创新高时移动峰值。从未下跌时回撤为零，收复日为空。已下跌但之后没有回到峰值时，收复日保持 null。空列表不能调用。
     */
    private DrawdownResult drawdown(List<NavObservation> observations) {
        NavObservation peak = observations.getFirst();
        NavObservation bestPeak = peak;
        NavObservation trough = peak;
        BigDecimal worst = BigDecimal.ZERO;
        for (NavObservation item : observations) {
            if (item.nav().compareTo(peak.nav()) > 0) {
                peak = item;
            }
            BigDecimal value = ratio(item.nav(), peak.nav());
            if (value.compareTo(worst) < 0) {
                worst = value;
                bestPeak = peak;
                trough = item;
            }
        }
        LocalDate recovery = null;
        if (worst.signum() < 0) {
            for (NavObservation item : observations) {
                if (item.date().isAfter(trough.date()) && item.nav().compareTo(bestPeak.nav()) >= 0) {
                    recovery = item.date();
                    break;
                }
            }
        }
        return new DrawdownResult(scale(worst), new DrawdownPeriod(bestPeak.date(), trough.date(), recovery));
    }

    /**
     * 计算收益的样本标准差。
     * 列表为空时平均数会抛出 {@link java.util.NoSuchElementException}。只有一个收益时除以零，得到非数值，由调用方标成无效数值。
     */
    private double sampleStdDev(List<BigDecimal> values) {
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElseThrow();
        double sum = values.stream().mapToDouble(value -> {
            double delta = value.doubleValue() - mean;
            return delta * delta;
        }).sum();
        return StrictMath.sqrt(sum / (values.size() - 1));
    }

    /**
     * 计算当前值相对前值的简单收益率并缩放到 8 位小数。
     * 前值为零时除法抛出 {@link ArithmeticException}；净值序列不允许非正净值，正常路径不会触发。
     */
    private BigDecimal ratio(BigDecimal current, BigDecimal previous) {
        return scale(current.divide(previous, MC).subtract(BigDecimal.ONE));
    }

    /**
     * 把已缩放的数值包装成可用指标。
     * 不额外判断数值是否有限；非有限值应先走无效数值分支。
     */
    private MetricValue available(BigDecimal value) {
        return MetricValue.available(scale(value));
    }

    /**
     * 按给定原因码包装不可用指标。
     * 原因码为 null 时仍会生成不可用结果，调用方将无法区分失败类型。
     */
    private MetricValue unavailable(String reason) {
        return MetricValue.unavailable(reason);
    }

    /**
     * 只接受有限双精度值并转成可用指标。
     * 无穷或非数值返回无效数值，避免把溢出写成一个巨大的收益。
     */
    private MetricValue finite(double value) {
        if (!Double.isFinite(value)) {
            return unavailable("INVALID_NUMERIC_RESULT");
        }
        return available(BigDecimal.valueOf(value));
    }

    /**
     * 把金额缩放到 8 位小数，舍入方式为四舍五入。
     * 不拒绝 null；null 会在缩放时抛出 {@link NullPointerException}。
     */
    private BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 同时带走回撤数值和对应区间，避免两个结果在调用处错配。
     * 区间可以没有收复日。数值在放入之前已经缩放过。
     */
    private record DrawdownResult(BigDecimal value, DrawdownPeriod period) {}
}
