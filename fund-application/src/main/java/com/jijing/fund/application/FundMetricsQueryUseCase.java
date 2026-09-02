package com.jijing.fund.application;

import com.jijing.fund.analytics.model.FundMetrics;
import java.time.LocalDate;

public interface FundMetricsQueryUseCase {
    FundMetrics calculate(String fundCode, LocalDate startDate, LocalDate endDate, String navBasis);
}

