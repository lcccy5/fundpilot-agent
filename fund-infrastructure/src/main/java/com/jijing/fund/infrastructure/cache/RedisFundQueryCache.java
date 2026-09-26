package com.jijing.fund.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 基金查询缓存。命令超时由 Redis 客户端决定，本类不另设。
 * 读失败（连接、超时、坏 JSON）记警告并返回空。值为 null 是未命中。
 * 写失败和逐出失败只记警告。历史键带版本号；读版本失败时版本按 0，可能读到旧键或未命中。
 * 档案生存时间约 6 小时，历史约 2 小时，都加 1 到 300 秒抖动。重复写入覆盖。
 */
@Component
@ConditionalOnProperty(prefix = "fund.cache", name = "redis-enabled", havingValue = "true", matchIfMissing = true)
public class RedisFundQueryCache implements FundQueryCache {
    private static final Logger log = LoggerFactory.getLogger(RedisFundQueryCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    /** 使用应用的 ObjectMapper，保证领域类型上的时间模块可用。 */
    public RedisFundQueryCache(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    /** 档案未命中和读失败对调用方都是空。 */
    @Override
    public Optional<FundProfile> getProfile(FundCode code) {
        return read(profileKey(code), FundProfile.class);
    }

    /** 档案缓存约 6 小时。 */
    @Override
    public void putProfile(FundProfile profile) {
        write(profileKey(profile.code()), profile, Duration.ofHours(6));
    }

    /** 历史 JSON 不是列表时按读失败处理，返回空。 */
    @Override
    public Optional<List<NavPoint>> getHistory(FundCode code, LocalDate start, LocalDate end) {
        try {
            String json = redis.opsForValue().get(historyKey(code, start, end));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(mapper.readValue(json, new TypeReference<List<NavPoint>>() {
            }));
        } catch (Exception ex) {
            log.warn("Redis history cache read failed for fundCode={}", code.value());
            return Optional.empty();
        }
    }

    /** 历史缓存约 2 小时。键里的版本在写入时读取。 */
    @Override
    public void putHistory(FundCode code, LocalDate start, LocalDate end, List<NavPoint> points) {
        write(historyKey(code, start, end), points, Duration.ofHours(2));
    }

    /**
     * 先把版本加一，再删档案。版本递增失败时历史键仍指向旧版本，本方法不再继续删档案。
     */
    @Override
    public void evict(FundCode code) {
        try {
            redis.opsForValue().increment(versionKey(code));
            redis.delete(profileKey(code));
        } catch (RuntimeException ex) {
            log.warn("Redis cache eviction failed for fundCode={}", code.value());
        }
    }

    /** 单对象读取。null 与异常都变成空可选，但只有异常会打日志。 */
    private <T> Optional<T> read(String key, Class<T> type) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, type));
        } catch (Exception ex) {
            log.warn("Redis cache read failed for key={}", key);
            return Optional.empty();
        }
    }

    /** 在基础生存时间上加抖动，避免同一时刻集体过期。 */
    private void write(String key, Object value, Duration ttl) {
        try {
            long jitter = ThreadLocalRandom.current().nextLong(1, 301);
            redis.opsForValue().set(key, mapper.writeValueAsString(value), ttl.plusSeconds(jitter));
        } catch (Exception ex) {
            log.warn("Redis cache write failed for key={}", key);
        }
    }

    /** 版本缺失为 0。连接失败或非数字也当 0，调用方可能因此命中错误代际。 */
    private long version(FundCode code) {
        try {
            String value = redis.opsForValue().get(versionKey(code));
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** 档案键不含版本，逐出时直接删除。 */
    private String profileKey(FundCode code) {
        return "jijing:fund:profile:" + code.value();
    }

    /** 历史键含版本，旧版本靠过期消失，不在逐出时扫描。 */
    private String historyKey(FundCode code, LocalDate start, LocalDate end) {
        return "jijing:fund:nav:history:" + code.value() + ":" + version(code) + ":" + start + ":" + end;
    }

    /** 逐出时递增的代际计数。 */
    private String versionKey(FundCode code) {
        return "jijing:fund:version:" + code.value();
    }
}
