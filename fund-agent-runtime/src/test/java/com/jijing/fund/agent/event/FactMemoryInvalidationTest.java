package com.jijing.fund.agent.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.jijing.fund.agent.notification.InAppNotificationChannel;
import com.jijing.fund.agent.notification.InMemoryNotificationStore;
import com.jijing.fund.agent.notification.NotificationDispatcher;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FactMemoryInvalidationTest {
    @Test void sourceRevisionsDropOnlyTheMatchingFactFolders() {
        var dropped = new ArrayList<String>();
        AgentRuntimeRepository memory = new AgentRuntimeRepository() {
            @Override public void createConversation(String conversationId, Instant createdAt) {}
            @Override public boolean conversationExists(String conversationId) { return true; }
            @Override public String startRun(String conversationId, String requestId, String promptVersion, String promptHash, String toolSchemaVersion, String modelProvider, String modelName, Instant startedAt) { return "run"; }
            @Override public void completeRun(String runId, int modelRounds, int toolCalls, com.jijing.fund.agent.api.TokenUsage usage, long durationMs, Instant completedAt) {}
            @Override public void failRun(String runId, String status, String errorCode, String safeMessage, int toolCalls, long durationMs, Instant completedAt) {}
            @Override public void recordToolCall(com.jijing.fund.agent.port.AgentToolCallRecord record) {}
            @Override public void invalidateFactMemory(String fundCode, List<String> categories) {
                dropped.add(fundCode + ":" + String.join(",", categories));
            }
        };
        var dispatcher = new DomainEventDispatcher(new NotificationDispatcher(new InMemoryNotificationStore(), new InAppNotificationChannel()), memory);
        Instant now = Instant.parse("2026-09-25T02:00:00Z");
        dispatcher.dispatch(new DomainEvent("e1", "FUND_NAV_UPDATED", "fund", "000001", null, now, "v1", "nav-1", Map.of(), List.of(), "c"), now);
        dispatcher.dispatch(new DomainEvent("e2", "PORTFOLIO_TRANSACTION_RECORDED", "fund", "000001", "user-a", now, "v1", "tx-1", Map.of("portfolioId", "portfolio-1"), List.of(), "c"), now);
        dispatcher.dispatch(new DomainEvent("e3", "DOCUMENT_VERSION_ACTIVATED", "fund", "110022", null, now, "v1", "doc-1", Map.of(), List.of(), "c"), now);
        dispatcher.dispatch(new DomainEvent("e4", "PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED", "portfolio", "p1", "user-a", now, "v1", "dd-1", Map.of("previous", -1, "current", -2, "threshold", -10), List.of(), "c"), now);
        assertThat(dropped).containsExactly(
                "000001:NAV,PROFILE,METRICS",
                "000001:HOLDINGS,USER_CONTEXT",
                "portfolio-1:HOLDINGS,USER_CONTEXT",
                "110022:DOCUMENTS");
    }
}
