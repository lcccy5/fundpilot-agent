package com.jijing.fund.application.dto;

import com.jijing.fund.analytics.model.*;
import java.time.LocalDate;
import java.util.*;

public record FundComparisonResult(LocalDate commonStartDate, LocalDate commonEndDate, NavBasis navBasis,
        List<FundMetrics> funds, Map<String, List<MetricRanking>> rankings) {}

