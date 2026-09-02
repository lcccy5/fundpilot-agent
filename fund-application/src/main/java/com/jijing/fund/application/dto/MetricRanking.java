package com.jijing.fund.application.dto;

import java.math.BigDecimal;

public record MetricRanking(int rank, String fundCode, BigDecimal value, String unavailableReason) {}

