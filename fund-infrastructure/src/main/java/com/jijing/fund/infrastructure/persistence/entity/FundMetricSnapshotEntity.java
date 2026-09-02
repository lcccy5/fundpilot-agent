package com.jijing.fund.infrastructure.persistence.entity;

import java.math.BigDecimal;
import java.time.*;

public class FundMetricSnapshotEntity {
    public String fundCode,periodCode,navBasis,algorithmVersion;
    public LocalDate actualStartDate,actualEndDate;
    public Integer observationCount;
    public BigDecimal coverageRate,cumulativeReturn,annualizedReturn,annualizedVolatility,maxDrawdown,sharpeRatio,positiveDayRatio,riskFreeRate;
    public Long dataRevision;
    public Instant calculatedAt;
}

