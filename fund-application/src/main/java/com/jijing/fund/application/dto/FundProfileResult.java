package com.jijing.fund.application.dto;

import java.time.Instant;
import java.time.LocalDate;

public record FundProfileResult(String fundCode, String name, String fundType, String managementCompany,
        String fundManager, LocalDate establishedDate, String dataSource, Instant sourceUpdatedAt,
        Instant collectedAt, String freshness) {}
