package com.jijing.fund.agent.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Small deterministic note used to resolve cross-turn fund and period references. */
public record AgentConversationState(
        String conversationId,
        String activeFund,
        List<String> mentionedFunds,
        LocalDate periodStart,
        LocalDate periodEnd,
        String activeTopic,
        Instant updatedAt) {
    public AgentConversationState {
        mentionedFunds = mentionedFunds == null ? List.of() : List.copyOf(mentionedFunds);
    }

    public static AgentConversationState empty(String conversationId) {
        return new AgentConversationState(conversationId, null, List.of(), null, null, null, Instant.EPOCH);
    }
}
