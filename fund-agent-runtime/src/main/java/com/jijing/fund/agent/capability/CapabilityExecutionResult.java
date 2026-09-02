package com.jijing.fund.agent.capability;

import java.util.List;

/** 在 Agent 运行时边界间传递 CapabilityExecutionResult 数据的不可变值对象。 */
public record CapabilityExecutionResult(String outputUri, List<String> evidenceIds) {
    public CapabilityExecutionResult {
        if (outputUri == null || outputUri.isBlank()) throw new IllegalArgumentException("outputUri is required");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
