package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.*;

public record FundMetrics(FundCode fundCode, LocalDate requestedStartDate, LocalDate requestedEndDate,
        LocalDate actualStartDate, LocalDate actualEndDate, NavBasis navBasis, int observationCount,
        DataCoverage coverage, MetricValue cumulativeReturn, MetricValue annualizedReturn,
        MetricValue annualizedVolatility, MetricValue maxDrawdown, DrawdownPeriod maxDrawdownPeriod,
        MetricValue sharpeRatio, MetricValue positiveDayRatio, MetricValue bestDailyReturn,
        MetricValue worstDailyReturn, BigDecimal annualRiskFreeRate, String algorithmVersion,
        String dataVersion, Instant calculatedAt) {}

