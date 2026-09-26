package com.jijing.fund.application;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricStatus;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.application.dto.FundComparisonResult;
import com.jijing.fund.application.dto.MetricRanking;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NoOverlappingPeriodException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 多基金对比的应用服务。
 * 先按调用方区间取指标以发现共同重叠段，再在重叠段上重算，并给出收益与风险排名。
 */
public class FundComparisonApplicationService implements FundComparisonUseCase {
    private final FundMetricsQueryUseCase metrics;

    /**
     * 装配指标查询端口。对比本身不直接访问仓储。
     * 端口为空时留到比较调用失败。
     */
    public FundComparisonApplicationService(FundMetricsQueryUseCase metrics) {
        this.metrics = metrics;
    }

    /**
     * 比较去重并排序后的基金列表。
     * 列表为 null，或去重后少于两只、多于十只时抛出无效查询，且不会计算指标。
     * 实际起止日期没有交集时抛出无重叠区间；各基金净值口径不一致时抛出不支持的净值口径。
     * 单只指标计算自身的失败会原样传播。
     */
    @Override
    public FundComparisonResult compare(List<String> input, LocalDate start, LocalDate end, String basis) {
        if (input == null) {
            throw new InvalidFundQueryException("fundCodes are required");
        }
        List<String> codes = input.stream().distinct().sorted().toList();
        if (codes.size() < 2 || codes.size() > 10) {
            throw new InvalidFundQueryException("Comparison requires 2 to 10 unique funds");
        }
        List<FundMetrics> first = codes.stream().map(code -> metrics.calculate(code, start, end, basis)).toList();
        LocalDate commonStart = first.stream()
                .map(FundMetrics::actualStartDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElseThrow(NoOverlappingPeriodException::new);
        LocalDate commonEnd = first.stream()
                .map(FundMetrics::actualEndDate)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElseThrow(NoOverlappingPeriodException::new);
        if (commonStart.isAfter(commonEnd)) {
            throw new NoOverlappingPeriodException();
        }
        NavBasis resolved = first.getFirst().navBasis();
        if (first.stream().anyMatch(item -> item.navBasis() != resolved)) {
            throw new UnsupportedNavBasisException("Funds cannot be compared with mixed NAV basis");
        }
        List<FundMetrics> comparable = codes.stream()
                .map(code -> metrics.calculate(code, commonStart, commonEnd, resolved.name()))
                .toList();
        Map<String, List<MetricRanking>> rankings = new LinkedHashMap<>();
        rankings.put("cumulativeReturn", rank(comparable, FundMetrics::cumulativeReturn, false, false));
        rankings.put("annualizedVolatility", rank(comparable, FundMetrics::annualizedVolatility, true, false));
        rankings.put("maxDrawdown", rank(comparable, FundMetrics::maxDrawdown, true, true));
        rankings.put("sharpeRatio", rank(comparable, FundMetrics::sharpeRatio, false, false));
        return new FundComparisonResult(commonStart, commonEnd, resolved, comparable, rankings);
    }

    /**
     * 按一项指标给基金排名。不可用的指标排在可用指标之后，同组内再按代码稳定排序。
     * 升序用于波动和回撤这类越小越好的指标；绝对值为真时按数值绝对值比较。提取结果为空时在读取状态处失败。
     */
    private List<MetricRanking> rank(List<FundMetrics> values, Function<FundMetrics, MetricValue> extractor,
            boolean ascending, boolean absoluteValue) {
        Comparator<FundMetrics> comparator = (left, right) -> {
            MetricValue leftMetric = extractor.apply(left);
            MetricValue rightMetric = extractor.apply(right);
            boolean leftAvailable = leftMetric.status() == MetricStatus.AVAILABLE;
            boolean rightAvailable = rightMetric.status() == MetricStatus.AVAILABLE;
            if (leftAvailable != rightAvailable) {
                return leftAvailable ? -1 : 1;
            }
            if (!leftAvailable) {
                return left.fundCode().value().compareTo(right.fundCode().value());
            }
            var leftValue = absoluteValue ? leftMetric.value().abs() : leftMetric.value();
            var rightValue = absoluteValue ? rightMetric.value().abs() : rightMetric.value();
            int comparison = leftValue.compareTo(rightValue);
            if (ascending) {
                return comparison;
            }
            return -comparison;
        };
        List<FundMetrics> sorted = new ArrayList<>(values);
        sorted.sort(comparator.thenComparing(item -> item.fundCode().value()));
        var result = new ArrayList<MetricRanking>();
        for (int i = 0; i < sorted.size(); i++) {
            MetricValue metric = extractor.apply(sorted.get(i));
            result.add(new MetricRanking(i + 1, sorted.get(i).fundCode().value(), metric.value(),
                    metric.unavailableReason()));
        }
        return List.copyOf(result);
    }
}
