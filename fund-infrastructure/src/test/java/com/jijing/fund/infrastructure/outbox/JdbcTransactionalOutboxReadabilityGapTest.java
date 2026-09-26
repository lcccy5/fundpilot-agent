package com.jijing.fund.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.event.DomainEvent;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcTransactionalOutboxReadabilityGapTest {
    @Test
    void duplicateConsumptionDoesNotPublish() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionalOutbox outbox = new JdbcTransactionalOutbox(jdbc, new ObjectMapper());

        assertThat(outbox.consumeIdempotent("default-dispatcher", "event-1", Instant.parse("2026-09-01T00:00:00Z")))
                .isFalse();
        verify(jdbc, never()).update(contains("PUBLISHED"), any(Object[].class));
    }

    @Test
    void appendIgnoresDuplicateKeysAndStoresEmptyJsonWhenPayloadCannotBeSerialized() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        JdbcTransactionalOutbox outbox = new JdbcTransactionalOutbox(jdbc, new ObjectMapper());
        Map<String, Object> payload = new HashMap<>();
        payload.put("self", payload);
        DomainEvent event = new DomainEvent("event-1", "type", "aggregate", "id", "owner",
                Instant.parse("2026-09-01T00:00:00Z"), "v1", "dedupe", payload, List.of("evidence"), null);

        outbox.append(event);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("INSERT IGNORE INTO outbox_event");
        assertThat(args.getValue()).contains("[]");
    }
}
