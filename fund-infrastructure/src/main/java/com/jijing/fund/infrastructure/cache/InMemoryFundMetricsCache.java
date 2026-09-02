package com.jijing.fund.infrastructure.cache;

import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricsCache;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.cache",name="redis-enabled",havingValue="false")
public class InMemoryFundMetricsCache implements FundMetricsCache {
    private final ConcurrentHashMap<String,FundMetrics> values=new ConcurrentHashMap<>();
    @Override public Optional<FundMetrics> get(FundMetricCacheKey key){return Optional.ofNullable(values.get(String.valueOf(key)));}
    @Override public void put(FundMetricCacheKey key,FundMetrics metrics){values.put(String.valueOf(key),metrics);}
}
