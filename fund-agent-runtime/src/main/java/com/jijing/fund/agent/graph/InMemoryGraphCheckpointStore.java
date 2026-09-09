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

    /** Updates an existing checkpoint without changing its sequence in local and test runs. */
    @Override public void replace(GraphCheckpoint checkpoint) {
        int index = values.indexOf(values.stream().filter(value -> value.checkpointId().equals(checkpoint.checkpointId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("checkpoint does not exist")));
        values.set(index, checkpoint);
    }

    /** Supplies oldest-to-newest history because LangGraph4j restores by checkpoint id or latest step. */
    @Override public List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion) {
        return values.stream().filter(value -> value.runId().equals(runId) && value.taskId().equals(taskId)
                        && value.graphName().equals(graphName) && value.graphVersion().equals(graphVersion))
                .sorted(Comparator.comparingLong(GraphCheckpoint::sequence)).toList();
    }

    /** Selects the newest snapshot that was created by the requested graph version. */
    @Override 
    /** 获取当前 Agent 操作所需的 findLatest 结果。 */
    public Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion) {
        return findAll(runId, taskId, graphName, graphVersion).stream().max(Comparator.comparingLong(GraphCheckpoint::sequence));
    }
}
