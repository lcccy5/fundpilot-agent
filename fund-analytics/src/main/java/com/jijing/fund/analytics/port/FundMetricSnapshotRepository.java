package com.jijing.fund.analytics.port;

import com.jijing.fund.analytics.model.FundMetricSnapshot;

public interface FundMetricSnapshotRepository {
    void upsert(FundMetricSnapshot snapshot);
}

