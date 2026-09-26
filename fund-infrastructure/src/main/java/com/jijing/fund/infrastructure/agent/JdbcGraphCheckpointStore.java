package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.GraphCheckpoint;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 持久化 LangGraph4j 在节点之后的状态，使租约内的任务可以接着做，而不必重放已完成的工具。
 * 追加遇到相同序列的重复提交时，把唯一键冲突转成 {@code duplicate graph checkpoint sequence}，不覆盖恢复点。
 * 替换只在同一检查点编号仍存在时成功，影响行数不是 1 则认为检查点不存在。
 * 状态无法序列化或无法读回时抛出，避免把不可恢复的状态报成可恢复。没有 HTTP 超时。
 */
@Repository
public class JdbcGraphCheckpointStore implements GraphCheckpointStore {
    private static final TypeReference<Map<String, Object>> STATE = new TypeReference<>() {
    };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 使用应用统一的 JSON 序列化器，保证图状态和别的表字段写法一致。 */
    public JdbcGraphCheckpointStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 只追加。重复序列失败，而不是静默盖掉恢复状态。 */
    @Override
    public void append(GraphCheckpoint checkpoint) {
        try {
            jdbc.update("""
                    INSERT INTO agent_graph_checkpoint(checkpoint_id,run_id,task_id,graph_name,graph_version,sequence,node_name,phase,state_json,created_at)
                    VALUES(?,?,?,?,?,?,?,?,CAST(? AS JSON),?)
                    """, checkpoint.checkpointId(), checkpoint.runId(), checkpoint.taskId(), checkpoint.graphName(),
                    checkpoint.graphVersion(), checkpoint.sequence(), checkpoint.nodeName(), checkpoint.phase(),
                    json(checkpoint.state()), Timestamp.from(checkpoint.createdAt()));
        } catch (DuplicateKeyException error) {
            throw new IllegalStateException("duplicate graph checkpoint sequence", error);
        }
    }

    /** 仅当框架按同一检查点编号重写时更新。运行、任务、图名和版本必须同时匹配。 */
    @Override
    public void replace(GraphCheckpoint checkpoint) {
        int updated = jdbc.update("""
                UPDATE agent_graph_checkpoint SET node_name=?,phase=?,state_json=CAST(? AS JSON),created_at=?
                WHERE checkpoint_id=? AND run_id=? AND task_id=? AND graph_name=? AND graph_version=?
                """, checkpoint.nodeName(), checkpoint.phase(), json(checkpoint.state()),
                Timestamp.from(checkpoint.createdAt()), checkpoint.checkpointId(), checkpoint.runId(),
                checkpoint.taskId(), checkpoint.graphName(), checkpoint.graphVersion());
        if (updated != 1) {
            throw new IllegalStateException("checkpoint does not exist");
        }
    }

    /** 按执行顺序返回该图版本的全部检查点，供框架自己恢复。 */
    @Override
    public List<GraphCheckpoint> findAll(String runId, String taskId, String graphName, String graphVersion) {
        return jdbc.query("""
                SELECT checkpoint_id,run_id,task_id,graph_name,graph_version,sequence,node_name,phase,CAST(state_json AS CHAR),created_at
                FROM agent_graph_checkpoint WHERE run_id=? AND task_id=? AND graph_name=? AND graph_version=?
                ORDER BY sequence ASC
                """, (rs, index) -> new GraphCheckpoint(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6), rs.getString(7), rs.getString(8),
                state(rs.getString(9)), rs.getTimestamp(10).toInstant()), runId, taskId, graphName, graphVersion);
    }

    /** 只取同一图版本中序列最大的一条，图版本变化后不会接着旧状态跑。 */
    @Override
    public Optional<GraphCheckpoint> findLatest(String runId, String taskId, String graphName, String graphVersion) {
        return findAll(runId, taskId, graphName, graphVersion).stream()
                .max(java.util.Comparator.comparingLong(GraphCheckpoint::sequence));
    }

    /** 序列化失败必须失败关闭，不能把“没写上”说成可恢复。 */
    private String json(Map<String, Object> state) {
        try {
            return mapper.writeValueAsString(state);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize graph checkpoint", error);
        }
    }

    /** 读回成通用 Map。坏 JSON 不能当成空状态继续执行。 */
    private Map<String, Object> state(String json) {
        try {
            return mapper.readValue(json, STATE);
        } catch (Exception error) {
            throw new IllegalStateException("cannot restore graph checkpoint", error);
        }
    }
}
