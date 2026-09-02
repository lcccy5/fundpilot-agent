package com.jijing.fund.agent.graph;

import java.util.Optional;

/** Durable boundary for graph-node state; implementations must preserve sequence ordering per task. */
public interface GraphCheckpointStore {
    /** Persists one post-node snapshot and rejects duplicate task/sequence pairs. */
    void append(GraphCheckpoint checkpoint);

    /** Returns the latest compatible snapshot for a task, or empty when execution starts fresh. */
    Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion);
}
