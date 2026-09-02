package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.time.LocalDate;
import java.util.*;

public record NavSeries(FundCode fundCode, NavBasis navBasis, LocalDate requestedStartDate,
        LocalDate requestedEndDate, List<NavObservation> observations, DataCoverage coverage, String dataVersion) {
    public NavSeries {
        Objects.requireNonNull(fundCode); Objects.requireNonNull(navBasis); Objects.requireNonNull(requestedStartDate);
        Objects.requireNonNull(requestedEndDate); Objects.requireNonNull(observations); Objects.requireNonNull(coverage); Objects.requireNonNull(dataVersion);
        if (requestedStartDate.isAfter(requestedEndDate)) throw new IllegalArgumentException("startDate must not be after endDate");
        var sorted = new ArrayList<>(observations); sorted.sort(Comparator.comparing(NavObservation::date));
        LocalDate previous = null;
        for (NavObservation item : sorted) {
            if (item.date().isBefore(requestedStartDate) || item.date().isAfter(requestedEndDate)) throw new IllegalArgumentException("observation outside requested range");
            if (item.date().equals(previous)) throw new IllegalArgumentException("duplicate observation date"); previous = item.date();
        }
        observations = List.copyOf(sorted);
    }
}

