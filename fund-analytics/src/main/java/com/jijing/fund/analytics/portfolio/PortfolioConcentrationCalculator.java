package com.jijing.fund.analytics.portfolio;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * 用正市值计算最大权重、前三大权重合计和赫芬达尔指数。
 * 没有正市值时状态为不可用，权重字段为空，不用零假装已经分散。同一组市值重复计算得到相等结果。
 */
public final class PortfolioConcentrationCalculator {

    /**
     * 忽略 null、零和负市值后计算集中度。
     * 入参为 null、过滤后为空，或合计不大于零时，返回不可用结果且三个权重都为 null。
     * 可用时最大权重取排序后的第一项，前三大不足三只时只累加现有权重。重复调用同一列表结果相等。
     */
    public Result calculate(List<BigDecimal> positionValues) {
        List<BigDecimal> positive;
        if (positionValues == null) {
            positive = List.of();
        } else {
            positive = positionValues.stream()
                    .filter(value -> value != null && value.signum() > 0)
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        BigDecimal total = positive.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            return new Result(null, null, null, "UNAVAILABLE");
        }
        var weights = positive.stream().map(value -> value.divide(total, 8, RoundingMode.HALF_UP)).toList();
        BigDecimal max = weights.getFirst();
        BigDecimal top3 = weights.stream().limit(3).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal hhi = weights.stream()
                .map(weight -> weight.multiply(weight))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(8, RoundingMode.HALF_UP);
        return new Result(max, top3, hhi, "AVAILABLE");
    }

    /**
     * 最大单基权重、前三大权重合计、赫芬达尔指数，以及可用或不可用状态。
     * 不可用时三个数值都为 null。状态是普通字符串，拼写错误不会在构造时被拒绝。
     */
    public record Result(BigDecimal maxFundWeight, BigDecimal top3Weight, BigDecimal hhi, String status) {}
}
