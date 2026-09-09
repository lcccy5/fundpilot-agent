package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.GraphCheckpoint;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists post-node LangGraph4j state so a leased task can resume without repeating completed tools. */
@Repository
public class JdbcGraphCheckpointStore implements GraphCheckpointStore {
    private static final TypeReference<Map<String, Object>> STATE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the store with the application's canonical JSON serializer. */
    public JdbcGraphCheckpointStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    /** Writes one append-only checkpoint; duplicate sequences fail instead of silently overwriting recovery state. */
    @Override public void append(GraphCheckpoint checkpoint) {
        try {
            jdbc.update("""
                    INSERT INTO agent_graph_checkpoint(checkpoint_id,run_id,task_id,graph_name,graph_version,sequence,node_name,phase,state_json,created_at)
                    VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?)
                    """, checkpoint.checkpointId(), checkpoint.runId(), checkpoint.taskId(), checkpoint.graphName(), checkpoint.graphVersion(),
                    checkpoint.sequence(), checkpoint.nodeName(), checkpoint.phase(), json(checkpoint.state()), Timestamp.from(checkpoint.createdAt()));
        } catch (DuplicateKeyException error) {
            throw new IllegalStateException("duplicate graph checkpoint sequence", error);
        }
    }

    /** Updates a checkpoint only when LangGraph4j rewrites the same checkpoint id. */
    @Override public void replace(GraphCheckpoint checkpoint) {
        int updated = jdbc.update("""
                UPDATE agent_graph_checkpoint SET node_name=?,phase=?,state_json=CAST(? AS JSON),created_at=?
                WHERE checkpoint_id=? AND run_id=? AND task_id=? AND graph_name=? AND graph_version=?
                """, checkpoint.nodeName(), checkpoint.phase(), json(checkpoint.state()), Timestamp.from(checkpoint.createdAt()),
                checkpoint.checkpointId(), checkpoint.runId(), checkpoint.taskId(), checkpoint.graphName(), checkpoint.graphVersion());
        if (updated != 1) throw new IllegalStateException("checkpoint does not exist");
    }

    /** Loads the complete native history in execution order for framework-managed resume. */
    @Override public java.util.List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion) {
        return jdbc.query("""
                SELECT checkpoint_id,run_id,task_id,graph_name,graph_version,sequence,node_name,phase,CAST(state_json AS CHAR),created_at
                FROM agent_graph_checkpoint WHERE run_id=? AND task_id=? AND graph_name=? AND graph_version=?
                ORDER BY sequence ASC
                """, (rs, index) -> new GraphCheckpoint(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getLong(6), rs.getString(7), rs.getString(8), state(rs.getString(9)), rs.getTimestamp(10).toInstant()),
                runId, taskId, graphName, graphVersion);
    }

    /** Loads the newest state emitted by the same graph version, preventing incompatible resumes after a graph change. */
    @Override public Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion) {
        return findAll(runId, taskId, graphName, graphVersion).stream().max(java.util.Comparator.comparingLong(GraphCheckpoint::sequence));
    }

    /** Serializes graph state fail-closed because an unpersistable state must not be reported as recoverable. */
    private String json(Map<String, Object> state) {
        try { return mapper.writeValueAsString(state); }
        catch (Exception error) { throw new IllegalStateException("cannot serialize graph checkpoint", error); }
    }

    /** Parses checkpoint JSON into generic, serializable graph values. */
    private Map<String, Object> state(String json) {
        try { return mapper.readValue(json, STATE); }
        catch (Exception error) { throw new IllegalStateException("cannot restore graph checkpoint", error); }
    }
}
