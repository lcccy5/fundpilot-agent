package com.jijing.fund.agent.api;

import java.time.Instant;

/** One owned research run shown in the history list. */
public record AgentResearchHistoryItem(String runId, String status, String message, String fundCode, Instant startedAt) {}
