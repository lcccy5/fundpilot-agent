package com.jijing.fund.agent.graph;

import java.time.Instant;
import java.util.Map;

/**
 * LangGraph4j 节点产出可序列化状态后的不可变恢复点。序号和检查点标识一起决定能否安全恢复。
 * 身份字段缺失时拒绝创建，避免写出无法定位的快照。
 */
public record GraphCheckpoint(String checkpointId, String runId, String taskId, String graphName, String graphVersion,
                              long sequence, String nodeName, String phase, Map<String, Object> state,
                              Instant createdAt) {
    /**
     * 校验恢复所需的身份、图版本、节点和创建时间，并冻结状态映射。
     * 任一必填字段为空白或 createdAt 为 null 时抛出 IllegalArgumentException。state 为 null 时收成空映射，不视为失败。
     */
    public GraphCheckpoint {
        if (checkpointId == null || checkpointId.isBlank() || runId == null || runId.isBlank() || taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("checkpointId, runId and taskId are required");
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
