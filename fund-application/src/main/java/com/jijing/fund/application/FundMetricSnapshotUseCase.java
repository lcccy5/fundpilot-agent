package com.jijing.fund.application;

import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import java.time.LocalDate;

public interface FundMetricSnapshotUseCase {
    MetricSnapshotBatchResult recomputeEnabledFunds(LocalDate endDate);
}

