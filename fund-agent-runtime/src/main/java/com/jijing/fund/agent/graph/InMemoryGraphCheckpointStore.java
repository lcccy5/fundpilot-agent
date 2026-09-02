package com.jijing.fund.agent.graph;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe test and local fallback store; production uses the JDBC implementation. */
public final class InMemoryGraphCheckpointStore implements GraphCheckpointStore {
    /** Append-only history mirrors production recovery semantics without sharing mutable state. */
    private final List<GraphCheckpoint> values = new CopyOnWriteArrayList<>();

    /** Adds a graph snapshot, rejecting a duplicate sequence for the same task. */
    @Override 
    /** 通过 append 操作更新持久化或内存中的运行状态。 */
    public void append(GraphCheckpoint checkpoint) {
        boolean duplicate = values.stream().anyMatch(value -> value.runId().equals(checkpoint.runId())
                && value.taskId().equals(checkpoint.taskId()) && value.sequence() == checkpoint.sequence());
        if (duplicate) throw new IllegalStateException("duplicate graph checkpoint sequence");
        values.add(checkpoint);
    }

    /** Selects the newest snapshot that was created by the requested graph version. */
    @Override 
    /** 获取当前 Agent 操作所需的 findLatest 结果。 */
    public Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion) {
        return values.stream().filter(value -> value.runId().equals(runId) && value.taskId().equals(taskId)
                        && value.graphName().equals(graphName) && value.graphVersion().equals(graphVersion))
                .max(Comparator.comparingLong(GraphCheckpoint::sequence));
    }
}
