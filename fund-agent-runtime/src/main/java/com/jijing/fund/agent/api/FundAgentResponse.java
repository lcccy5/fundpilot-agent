package com.jijing.fund.agent.api;

import java.time.Instant;
import java.util.List;

/** 在 Agent 运行时边界间传递 FundAgentResponse 数据的不可变值对象。 */
public record FundAgentResponse(String conversationId, String runId, String answer,
        List<EvidenceReference> evidence, List<String> limitations, String promptVersion,
        String modelProvider, String modelName, TokenUsage usage, Instant completedAt) {
    public FundAgentResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
