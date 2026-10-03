package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEvent;
import com.jijing.fund.agent.notification.NotificationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonAgentEventClientTest {
    @TempDir Path directory;

    @Test void sendsIndependentCredentialAndPersistsPythonDecision() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var file = directory.resolve("event.key");
            Files.writeString(file, "test-event-key");
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"deadLetter":false,"reason":"FIRED","shouldNotify":true,"held":false,
                 "ownerUserId":"alice","ruleId":"drawdown","fingerprint":"stable"}
                """));
            var store = mock(NotificationStore.class);
            var client = new PythonAgentEventClient(store,server.url("/").toString(),"",file.toString());
            var timestamp = Instant.parse("2026-09-30T00:00:00Z");
            var event = new DomainEvent("one","PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED","portfolio","p",
                    "alice",timestamp,"v1","one",Map.of("previous",-5,"current",-12),List.of(),null);
            assertTrue(client.dispatch(event,timestamp).shouldNotify());
            var request = server.takeRequest();
            assertEquals("/internal/agent/events",request.getPath());
            assertEquals("test-event-key",request.getHeader("X-Agent-Event-Key"));
            assertNull(request.getHeader("Authorization"));
            verify(store).record("alice","drawdown","stable",false,timestamp);
        }
    }

    @Test void missingCredentialDoesNotConsumeEvent() {
        var client = new PythonAgentEventClient(mock(NotificationStore.class),"http://127.0.0.1:1","",
                directory.resolve("missing.key").toString());
        assertThrows(IllegalStateException.class, () -> client.dispatch(null,Instant.now()));
    }
}
