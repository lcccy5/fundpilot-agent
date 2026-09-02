package com.jijing.fund.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record NavPointResult(LocalDate navDate, BigDecimal unitNav, BigDecimal accumulatedNav, BigDecimal dailyChangeRate) {}

