package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEvent;
import com.jijing.fund.agent.event.DomainEventDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonOutboxWorkerTest {
    @Test void retriesRemoteFailureWithoutExecutingLegacyAgent() {
        var outbox = mock(JdbcTransactionalOutbox.class);
        var legacy = mock(DomainEventDispatcher.class);
        var python = mock(PythonAgentEventClient.class);
        var event = new DomainEvent("one","UPDATED","fund","000001",null,Instant.now(),"v1","one",Map.of(),List.of(),null);
        when(outbox.claimPending(anyString(),any())).thenReturn(Optional.of(event));
        when(python.dispatch(eq(event),any())).thenThrow(new IllegalStateException("offline"));
        new OutboxPublisherWorker(outbox,legacy,python,"python").tick();
        verify(outbox).retryWait(eq("one"),any());
        verify(outbox,never()).consumeIdempotent(anyString(),anyString(),any());
        verifyNoInteractions(legacy);
    }
}
