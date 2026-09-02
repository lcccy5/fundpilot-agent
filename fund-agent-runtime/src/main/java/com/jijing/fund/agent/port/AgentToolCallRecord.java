package com.jijing.fund.agent.port;

import java.time.Instant;
import java.util.List;

/** 在 Agent 运行时边界间传递 AgentToolCallRecord 数据的不可变值对象。 */
public record AgentToolCallRecord(String runId, String toolName, String toolVersion,
        String argumentHash, String argumentsRedactedJson, String resultStatus,
        List<String> evidenceIds, String errorCode, long durationMs, Instant startedAt, Instant completedAt) {}
