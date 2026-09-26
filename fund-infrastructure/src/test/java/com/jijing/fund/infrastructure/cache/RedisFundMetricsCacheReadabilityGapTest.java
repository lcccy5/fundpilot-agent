package com.jijing.fund.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.analytics.model.FundMetricCacheKey;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisFundMetricsCacheReadabilityGapTest {
    private final FundMetricCacheKey key = new FundMetricCacheKey(new FundCode("000001"), LocalDate.parse("2026-01-01"),
            LocalDate.parse("2026-01-31"), NavBasis.UNIT_NAV, new BigDecimal("0.02"), "data", "algo");

    @Test
    void connectionFailureAndBrokenJsonLookLikeAMiss() {
        ValueOperations<String, String> values = operations();
        doThrow(new RedisConnectionFailureException("down")).when(values).get(anyString());
        assertThat(cache(values).get(key)).isEmpty();

        doReturn(null).when(values).get(anyString());
        assertThat(cache(values).get(key)).isEmpty();

        doReturn("{").when(values).get(anyString());
        assertThat(cache(values).get(key)).isEmpty();
    }

    @Test
    void connectionFailureOnWriteIsSwallowed() {
        ValueOperations<String, String> values = operations();
        doThrow(new RedisConnectionFailureException("down")).when(values)
                .set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache(values).put(key, null)).doesNotThrowAnyException();
    }

    @SuppressWarnings("unchecked")
    private static ValueOperations<String, String> operations() {
        return mock(ValueOperations.class);
    }

    private static RedisFundMetricsCache cache(ValueOperations<String, String> values) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(values);
        return new RedisFundMetricsCache(redis, new ObjectMapper());
    }
}
