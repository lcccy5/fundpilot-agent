package com.jijing.fund.application;

import com.jijing.fund.analytics.model.*;
import com.jijing.fund.application.dto.*;
import com.jijing.fund.application.exception.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

public class FundComparisonApplicationService implements FundComparisonUseCase {
    private final FundMetricsQueryUseCase metrics;
    public FundComparisonApplicationService(FundMetricsQueryUseCase metrics) { this.metrics=metrics; }
    @Override public FundComparisonResult compare(List<String> input, LocalDate start, LocalDate end, String basis) {
        if (input == null) throw new InvalidFundQueryException("fundCodes are required");
        List<String> codes = input.stream().distinct().sorted().toList();
        if (codes.size()<2 || codes.size()>10) throw new InvalidFundQueryException("Comparison requires 2 to 10 unique funds");
        List<FundMetrics> first = codes.stream().map(code -> metrics.calculate(code,start,end,basis)).toList();
        LocalDate commonStart = first.stream().map(FundMetrics::actualStartDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElseThrow(NoOverlappingPeriodException::new);
        LocalDate commonEnd = first.stream().map(FundMetrics::actualEndDate).filter(Objects::nonNull).min(LocalDate::compareTo).orElseThrow(NoOverlappingPeriodException::new);
        if (commonStart.isAfter(commonEnd)) throw new NoOverlappingPeriodException();
        NavBasis resolved = first.getFirst().navBasis();
        if (first.stream().anyMatch(m -> m.navBasis()!=resolved)) throw new UnsupportedNavBasisException("Funds cannot be compared with mixed NAV basis");
        List<FundMetrics> comparable = codes.stream().map(code -> metrics.calculate(code,commonStart,commonEnd,resolved.name())).toList();
        Map<String,List<MetricRanking>> rankings = new LinkedHashMap<>();
        rankings.put("cumulativeReturn", rank(comparable,FundMetrics::cumulativeReturn,false,false));
        rankings.put("annualizedVolatility", rank(comparable,FundMetrics::annualizedVolatility,true,false));
        rankings.put("maxDrawdown", rank(comparable,FundMetrics::maxDrawdown,true,true));
        rankings.put("sharpeRatio", rank(comparable,FundMetrics::sharpeRatio,false,false));
        return new FundComparisonResult(commonStart,commonEnd,resolved,comparable,rankings);
    }
    private List<MetricRanking> rank(List<FundMetrics> values, Function<FundMetrics,MetricValue> extractor,
            boolean ascending, boolean absoluteValue) {
        Comparator<FundMetrics> comparator = (left, right) -> {
            MetricValue a = extractor.apply(left);
            MetricValue b = extractor.apply(right);
            boolean aAvailable = a.status() == MetricStatus.AVAILABLE;
            boolean bAvailable = b.status() == MetricStatus.AVAILABLE;
            if (aAvailable != bAvailable) return aAvailable ? -1 : 1;
            if (!aAvailable) return left.fundCode().value().compareTo(right.fundCode().value());
            var aValue = absoluteValue ? a.value().abs() : a.value();
            var bValue = absoluteValue ? b.value().abs() : b.value();
            int comparison = aValue.compareTo(bValue);
            return ascending ? comparison : -comparison;
        };
        List<FundMetrics> sorted=new ArrayList<>(values); sorted.sort(comparator.thenComparing(m->m.fundCode().value()));
        var result=new ArrayList<MetricRanking>(); for(int i=0;i<sorted.size();i++){MetricValue v=extractor.apply(sorted.get(i));
            result.add(new MetricRanking(i+1,sorted.get(i).fundCode().value(),v.value(),v.unavailableReason()));} return List.copyOf(result);
    }
}
