package com.jijing.fund.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisFundQueryCacheReadabilityGapTest {
    @Test
    void connectionFailureOnReadLooksLikeAMiss() {
        RedisFundQueryCache cache = cache(operationsThatThrowOnRead());

        assertThat(cache.getProfile(new FundCode("000001"))).isEmpty();
    }

    @Test
    void nullAndBrokenJsonLookLikeAMiss() {
        ValueOperations<String, String> values = operations();
        when(values.get(anyString())).thenReturn(null);
        RedisFundQueryCache missing = cache(values);
        assertThat(missing.getProfile(new FundCode("000001"))).isEmpty();

        when(values.get(anyString())).thenReturn("{");
        RedisFundQueryCache broken = cache(values);
        assertThat(broken.getHistory(new FundCode("000001"), LocalDate.parse("2026-01-01"),
                LocalDate.parse("2026-01-31"))).isEmpty();
    }

    @Test
    void connectionFailureOnWriteAndEvictIsSwallowed() {
        ValueOperations<String, String> values = operations();
        when(values.get(anyString())).thenReturn(null);
        doThrow(new RedisConnectionFailureException("down")).when(values).set(anyString(), anyString(), any(Duration.class));
        StringRedisTemplate redis = template(values);
        RedisFundQueryCache cache = new RedisFundQueryCache(redis, mapper());
        FundProfile profile = new FundProfile(new FundCode("000001"), "样本", null, null, null, null, "mock",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));

        assertThatCode(() -> cache.putProfile(profile)).doesNotThrowAnyException();

        doThrow(new RedisConnectionFailureException("down")).when(values).increment(anyString());
        assertThatCode(() -> cache.evict(new FundCode("000001"))).doesNotThrowAnyException();
        verify(redis, never()).delete(anyString());
    }

    private static RedisFundQueryCache cache(ValueOperations<String, String> values) {
        return new RedisFundQueryCache(template(values), mapper());
    }

    private static ValueOperations<String, String> operationsThatThrowOnRead() {
        ValueOperations<String, String> values = operations();
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        return values;
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> operations() {
        return mock(ValueOperations.class);
    }

    private static StringRedisTemplate template(ValueOperations<String, String> values) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        return redis;
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
