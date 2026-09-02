package com.jijing.fund.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricsCache;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.cache",name="redis-enabled",havingValue="true",matchIfMissing=true)
public class RedisFundMetricsCache implements FundMetricsCache {
    private static final Logger log=LoggerFactory.getLogger(RedisFundMetricsCache.class);
    private final StringRedisTemplate redis;private final ObjectMapper mapper;
    public RedisFundMetricsCache(StringRedisTemplate redis,ObjectMapper mapper){this.redis=redis;this.mapper=mapper;}
    @Override public Optional<FundMetrics> get(FundMetricCacheKey key){try{String json=redis.opsForValue().get(redisKey(key));return json==null?Optional.empty():Optional.of(mapper.readValue(json,FundMetrics.class));}
        catch(Exception ex){log.warn("Metric cache read failed for fundCode={}",key.fundCode().value());return Optional.empty();}}
    @Override public void put(FundMetricCacheKey key,FundMetrics metrics){try{redis.opsForValue().set(redisKey(key),mapper.writeValueAsString(metrics),Duration.ofHours(2).plusSeconds(ThreadLocalRandom.current().nextLong(1,301)));}
        catch(Exception ex){log.warn("Metric cache write failed for fundCode={}",key.fundCode().value());}}
    private String redisKey(FundMetricCacheKey k){return "jijing:fund:metrics:"+k.fundCode().value()+":"+k.dataVersion()+":"+k.algorithmVersion()+":"+k.navBasis()+":"+k.startDate()+":"+k.endDate()+":"+k.riskFreeRate();}
}
