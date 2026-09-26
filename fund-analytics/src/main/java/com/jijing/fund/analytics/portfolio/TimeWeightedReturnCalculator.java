package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 把各子区间的增长因子连乘，得到时间加权收益率。
 * 样本缺失、期初价值无效或增长因子不为正时返回空，绝不用零代替。同一组子区间重复计算得到相等结果。
 */
public final class TimeWeightedReturnCalculator {

    /**
     * 连乘子区间增长因子后减去一。
     * 入参为 null 或空列表时返回空。任一子区间的期初或期末价值为 null、期初价值不大于零，或扣除外部现金流后的增长因子不大于零时，整段返回空。
     * 列表中的 null 元素会抛出 {@link NullPointerException}。不检查起止日期的先后；日期颠倒仍按金额计算。相同输入重复调用结果一致。
     */
    public Optional<BigDecimal> calculate(List<SubPeriod> periods) {
        if (periods == null || periods.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal product = BigDecimal.ONE;
        for (SubPeriod period : periods) {
            if (period.beginValue() == null || period.endValue() == null || period.beginValue().signum() <= 0) {
                return Optional.empty();
            }
            BigDecimal growth = period.endValue()
                    .subtract(period.externalFlow())
                    .divide(period.beginValue(), 12, RoundingMode.HALF_UP);
            if (growth.signum() <= 0) {
                return Optional.empty();
            }
            product = product.multiply(growth);
        }
        return Optional.of(product.subtract(BigDecimal.ONE).setScale(8, RoundingMode.HALF_UP));
    }

    /**
     * 两个估值日之间的期初价值、期末价值和外部现金流。
     * 外部现金流缺失时按零处理。起止日期缺失时不能构成子区间；期初和期末价值允许暂时为空，留到连乘阶段返回空结果。
     */
    public record SubPeriod(LocalDate start, LocalDate end, BigDecimal beginValue, BigDecimal endValue, BigDecimal externalFlow) {
        /**
         * 补齐外部现金流并拒绝缺失日期。
         * 起或止为 null 时抛出 {@link NullPointerException}。外部现金流为 null 时改写为零，不视为失败。不要求止日晚于起日。
         */
        public SubPeriod {
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            externalFlow = externalFlow == null ? BigDecimal.ZERO : externalFlow;
        }
    }
}
