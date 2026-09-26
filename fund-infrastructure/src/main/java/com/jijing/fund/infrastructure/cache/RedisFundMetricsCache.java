package com.jijing.fund.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.analytics.model.FundMetricCacheKey;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.port.FundMetricsCache;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 指标缓存。本类不设置单独的命令超时，超时表现取决于 Redis 客户端。
 * 读取遇到连接失败、超时或坏 JSON 时记警告并返回空，调用方会当缓存未命中。
 * 值为 null 也是未命中，不是错误。写入失败只记警告，不把指标计算失败传出去。
 * 生存时间约 2 小时再加 1 到 300 秒抖动。重复写入覆盖同一键。
 */
@Component
@ConditionalOnProperty(prefix = "fund.cache", name = "redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisFundMetricsCache implements FundMetricsCache {
    private static final Logger log = LoggerFactory.getLogger(RedisFundMetricsCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** 序列化使用应用的 ObjectMapper，以便日期和金额模块一致。 */
    public RedisFundMetricsCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** 空字符串以外的非法 JSON 与连接失败走同一条降级。 */
    @Override
    public Optional<FundMetrics> get(FundMetricCacheKey key) {
        try {
            String json = redis.opsForValue().get(redisKey(key));
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, FundMetrics.class));
        } catch (Exception ex) {
            log.warn("Metric cache read failed for fundCode={}", key.fundCode().value());
            return Optional.empty();
        }
    }

    /** 写失败不影响已经算好的指标返回给调用方。 */
    @Override
    public void put(FundMetricCacheKey key, FundMetrics metrics) {
        try {
            Duration ttl = Duration.ofHours(2).plusSeconds(ThreadLocalRandom.current().nextLong(1, 301));
            redis.opsForValue().set(redisKey(key), mapper.writeValueAsString(metrics), ttl);
        } catch (Exception ex) {
            log.warn("Metric cache write failed for fundCode={}", key.fundCode().value());
        }
    }

    /** 键包含代码、数据版本、算法、净值口径、区间和利率，避免不同计算混用。 */
    private String redisKey(FundMetricCacheKey key) {
        return "jijing:fund:metrics:" + key.fundCode().value() + ":" + key.dataVersion() + ":"
                + key.algorithmVersion() + ":" + key.navBasis() + ":" + key.startDate() + ":" + key.endDate() + ":"
                + key.riskFreeRate();
    }
}
