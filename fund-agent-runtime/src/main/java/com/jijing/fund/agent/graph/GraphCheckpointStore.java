package com.jijing.fund.agent.graph;

import java.util.List;
import java.util.Optional;

/** Durable boundary for graph-node state; implementations must preserve sequence ordering per task. */
public interface GraphCheckpointStore {
    /** Persists one post-node snapshot and rejects duplicate task/sequence pairs. */
    void append(GraphCheckpoint checkpoint);

    /** Replaces the framework checkpoint with the same id when LangGraph4j updates an existing step. */
    void replace(GraphCheckpoint checkpoint);

    /** Returns the complete ordered native checkpoint history required by LangGraph4j resume. */
    List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion);

    /** Returns the latest compatible snapshot for a task, or empty when execution starts fresh. */
    Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion);
}
