package com.jijing.fund.agent.graph;

import java.time.Instant;
import java.util.Map;

/** Immutable recovery point emitted after a LangGraph4j node has produced serializable state. */
public record GraphCheckpoint(String runId, String taskId, String graphName, String graphVersion,
                              long sequence, String nodeName, String phase, Map<String, Object> state,
                              Instant createdAt) {
    /** Validates the durable identity and freezes the graph state used for resume. */
    public GraphCheckpoint {
        if (runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("runId and taskId are required");
        }
        if (graphName == null || graphName.isBlank() || graphVersion == null || graphVersion.isBlank()) {
            throw new IllegalArgumentException("graph name and version are required");
        }
        if (nodeName == null || nodeName.isBlank() || phase == null || phase.isBlank() || createdAt == null) {
            throw new IllegalArgumentException("nodeName, phase and createdAt are required");
        }
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
