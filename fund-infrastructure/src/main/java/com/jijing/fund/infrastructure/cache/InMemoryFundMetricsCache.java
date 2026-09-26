package com.jijing.fund.infrastructure.cache;

import com.jijing.fund.analytics.model.FundMetricCacheKey;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.port.FundMetricsCache;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内指标缓存，仅在 Redis 关闭时启用。没有过期、超时或连接失败。
 * 键使用 {@link FundMetricCacheKey#toString()}，相同键再次写入会覆盖。
 */
@Component
@ConditionalOnProperty(prefix = "fund.cache", name = "redis-enabled", havingValue = "false")
public class InMemoryFundMetricsCache implements FundMetricsCache {
    private final ConcurrentHashMap<String, FundMetrics> values = new ConcurrentHashMap<>();

    /** 未写入时为空。 */
    @Override
    public Optional<FundMetrics> get(FundMetricCacheKey key) {
        return Optional.ofNullable(values.get(String.valueOf(key)));
    }

    /** 覆盖同一指标键。 */
    @Override
    public void put(FundMetricCacheKey key, FundMetrics metrics) {
        values.put(String.valueOf(key), metrics);
    }
}
