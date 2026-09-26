package com.jijing.fund.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcNotificationStoreReadabilityGapTest {
    @Test
    void duplicateFingerprintIsNotRecordedAgain() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcNotificationStore store = new JdbcNotificationStore(jdbc);

        assertThat(store.record("owner", "rule", "fingerprint", false, Instant.parse("2026-09-01T00:00:00Z")))
                .isFalse();
    }

    @Test
    void firstFingerprintIsRecorded() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        JdbcNotificationStore store = new JdbcNotificationStore(jdbc);

        assertThat(store.record("owner", "rule", "fingerprint", true, Instant.parse("2026-09-01T00:00:00Z")))
                .isTrue();
    }
}
