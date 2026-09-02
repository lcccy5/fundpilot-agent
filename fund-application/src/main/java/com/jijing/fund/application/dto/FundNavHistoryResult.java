package com.jijing.fund.application.dto;

import java.util.List;

public record FundNavHistoryResult(String fundCode, String dataSource, List<NavPointResult> items) {}

