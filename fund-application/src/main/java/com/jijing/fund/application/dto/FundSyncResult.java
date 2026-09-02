package com.jijing.fund.application.dto;

public record FundSyncResult(String fundCode, String dataSource, int fetchedCount, int savedCount, String status) {}

