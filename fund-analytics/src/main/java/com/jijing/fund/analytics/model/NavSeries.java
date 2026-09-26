package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 请求区间内、按日期升序且日期不重复的净值序列。
 * 构造时复制并排序观测，调用方之后修改原列表不会影响序列。空观测被允许，但后续收益计算会因样本不足而不可用。
 */
public record NavSeries(FundCode fundCode, NavBasis navBasis, LocalDate requestedStartDate,
        LocalDate requestedEndDate, List<NavObservation> observations, DataCoverage coverage, String dataVersion) {

    /**
     * 校验请求区间和观测，并保存排序后的不可变副本。
     * 任一必填组件为 null，或观测列表中含有 null 时抛出 {@link NullPointerException}。请求起始日晚于结束日、观测落在区间外或日期重复时抛出
     * {@link IllegalArgumentException}。起止日相同是合法边界。相同输入重复构造得到相等序列。
     */
    public NavSeries {
        Objects.requireNonNull(fundCode);
        Objects.requireNonNull(navBasis);
        Objects.requireNonNull(requestedStartDate);
        Objects.requireNonNull(requestedEndDate);
        Objects.requireNonNull(observations);
        Objects.requireNonNull(coverage);
        Objects.requireNonNull(dataVersion);
        if (requestedStartDate.isAfter(requestedEndDate)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }
        var sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparing(NavObservation::date));
        LocalDate previous = null;
        for (NavObservation item : sorted) {
            if (item.date().isBefore(requestedStartDate) || item.date().isAfter(requestedEndDate)) {
                throw new IllegalArgumentException("observation outside requested range");
            }
            if (item.date().equals(previous)) {
                throw new IllegalArgumentException("duplicate observation date");
            }
            previous = item.date();
        }
        observations = List.copyOf(sorted);
    }
}
