package com.jijing.fund.analytics.model;

import java.time.LocalDate;

public record DrawdownPeriod(LocalDate peakDate, LocalDate troughDate, LocalDate recoveryDate) {}

