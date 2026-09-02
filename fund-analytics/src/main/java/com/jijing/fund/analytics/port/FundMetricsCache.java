package com.jijing.fund.analytics.port;

import com.jijing.fund.analytics.model.*;
import java.util.Optional;

public interface FundMetricsCache {
    Optional<FundMetrics> get(FundMetricCacheKey key);
    void put(FundMetricCacheKey key, FundMetrics metrics);
}

