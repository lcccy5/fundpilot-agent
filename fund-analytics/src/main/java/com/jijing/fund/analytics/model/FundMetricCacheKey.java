package com.jijing.fund.analytics.model;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FundMetricCacheKey(FundCode fundCode, LocalDate startDate, LocalDate endDate,
        NavBasis navBasis, BigDecimal riskFreeRate, String dataVersion, String algorithmVersion) {}

