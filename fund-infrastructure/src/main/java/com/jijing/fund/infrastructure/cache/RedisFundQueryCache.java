package com.jijing.fund.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.cache",name="redis-enabled",havingValue="true",matchIfMissing=true)
public class RedisFundQueryCache implements FundQueryCache {
    private static final Logger log = LoggerFactory.getLogger(RedisFundQueryCache.class);
    private final StringRedisTemplate redis; private final ObjectMapper mapper;
    public RedisFundQueryCache(StringRedisTemplate redis, ObjectMapper mapper) { this.redis = redis; this.mapper = mapper; }
    @Override public Optional<FundProfile> getProfile(FundCode code) { return read(profileKey(code), FundProfile.class); }
    @Override public void putProfile(FundProfile profile) { write(profileKey(profile.code()), profile, Duration.ofHours(6)); }
    @Override public Optional<List<NavPoint>> getHistory(FundCode code, LocalDate start, LocalDate end) {
        try { String json = redis.opsForValue().get(historyKey(code, start, end));
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, new TypeReference<List<NavPoint>>() {}));
        } catch (Exception ex) { log.warn("Redis history cache read failed for fundCode={}", code.value()); return Optional.empty(); }
    }
    @Override public void putHistory(FundCode code, LocalDate start, LocalDate end, List<NavPoint> points) { write(historyKey(code, start, end), points, Duration.ofHours(2)); }
    @Override public void evict(FundCode code) { try { redis.opsForValue().increment(versionKey(code)); redis.delete(profileKey(code)); }
        catch (RuntimeException ex) { log.warn("Redis cache eviction failed for fundCode={}", code.value()); } }
    private <T> Optional<T> read(String key, Class<T> type) { try { String json = redis.opsForValue().get(key); return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, type)); }
        catch (Exception ex) { log.warn("Redis cache read failed for key={}", key); return Optional.empty(); } }
    private void write(String key, Object value, Duration ttl) { try { long jitter = ThreadLocalRandom.current().nextLong(1, 301);
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl.plusSeconds(jitter)); }
        catch (Exception ex) { log.warn("Redis cache write failed for key={}", key); } }
    private long version(FundCode code) { try { String value = redis.opsForValue().get(versionKey(code)); return value == null ? 0 : Long.parseLong(value); }
        catch (RuntimeException ex) { return 0; } }
    private String profileKey(FundCode c) { return "jijing:fund:profile:" + c.value(); }
    private String historyKey(FundCode c, LocalDate s, LocalDate e) { return "jijing:fund:nav:history:" + c.value() + ":" + version(c) + ":" + s + ":" + e; }
    private String versionKey(FundCode c) { return "jijing:fund:version:" + c.value(); }
}

