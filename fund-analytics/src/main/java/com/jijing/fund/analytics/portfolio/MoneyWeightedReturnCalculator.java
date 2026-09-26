package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 用固定区间二分求货币加权收益率。
 * 无法证明区间内有根、样本不足或数值溢出时返回空，不用零冒充收益率。同一现金流重复计算得到相等结果。
 */
public final class MoneyWeightedReturnCalculator {

    /**
     * 对带日期的现金流求年化内部收益率。
     * 入参为 null、笔数少于 2、缺少正现金流或缺少负现金流时返回空。两端净现值非有限或同号、中点净现值非有限，以及 200 次二分仍未落入容差时也返回空。
     * 列表中的 null 元素会在排序时抛出 {@link NullPointerException}。同一输入重复调用，空或数值结果保持一致。
     */
    public Optional<BigDecimal> calculate(List<CashFlow> flows) {
        if (flows == null || flows.size() < 2) {
            return Optional.empty();
        }
        var ordered = flows.stream().sorted(Comparator.comparing(CashFlow::date)).toList();
        if (ordered.stream().noneMatch(flow -> flow.amount().signum() > 0)
                || ordered.stream().noneMatch(flow -> flow.amount().signum() < 0)) {
            return Optional.empty();
        }
        double low = -0.9999;
        double high = 100d;
        double lowNpv = npv(ordered, low);
        double highNpv = npv(ordered, high);
        if (!Double.isFinite(lowNpv) || !Double.isFinite(highNpv) || lowNpv * highNpv > 0) {
            return Optional.empty();
        }
        for (int i = 0; i < 200; i++) {
            double mid = (low + high) / 2d;
            double value = npv(ordered, mid);
            if (!Double.isFinite(value)) {
                return Optional.empty();
            }
            if (Math.abs(value) < 1e-8) {
                return Optional.of(BigDecimal.valueOf(mid));
            }
            if (lowNpv * value <= 0) {
                high = mid;
                highNpv = value;
            } else {
                low = mid;
                lowNpv = value;
            }
        }
        return Optional.empty();
    }

    /**
     * 以首笔现金流日期为原点，按实际年分数累加折现额。
     * 利率使幂运算溢出时，结果可能是非有限值，由调用方决定放弃求解。日期早于首笔时指数为负，仍参与求和。
     */
    private double npv(List<CashFlow> flows, double rate) {
        LocalDate start = flows.getFirst().date();
        return flows.stream()
                .mapToDouble(flow -> flow.amount().doubleValue()
                        / StrictMath.pow(1d + rate, ChronoUnit.DAYS.between(start, flow.date()) / 365.2425d))
                .sum();
    }

    /**
     * 一笔发生在某日的有符号金额。
     * 日期或金额缺失时不能构成现金流；金额为零既不算流入也不算流出。
     */
    public record CashFlow(LocalDate date, BigDecimal amount) {
        /**
         * 拒绝缺失的日期和金额。
         * 任一为 null 时抛出 {@link NullPointerException}。不校验金额正负，也不校验日期是否重复。
         */
        public CashFlow {
            Objects.requireNonNull(date);
            Objects.requireNonNull(amount);
        }
    }
}
