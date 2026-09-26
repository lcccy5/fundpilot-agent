package com.jijing.fund.infrastructure.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.GraphCheckpoint;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcGraphCheckpointStoreReadabilityGapTest {
    @Test
    void duplicateSequenceIsRejected() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("duplicate sequence"));
        JdbcGraphCheckpointStore store = new JdbcGraphCheckpointStore(jdbc, new ObjectMapper());
        GraphCheckpoint checkpoint = new GraphCheckpoint("cp-1", "run-1", "task-1", "graph", "v1", 1, "node",
                "after", Map.of("ok", true), Instant.parse("2026-09-01T00:00:00Z"));

        assertThatThrownBy(() -> store.append(checkpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate graph checkpoint sequence")
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }
}
