package com.jijing.fund.infrastructure.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.knowledge.api.IndexRebuildView;
import com.jijing.fund.knowledge.port.IndexRebuildRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 索引重建记录。状态迁移要求当前状态匹配，否则视为并发冲突。
 * 校验报告序列化失败会抛出，读取失败则变成只含 {@code parseError} 的空报告，避免坏 JSON 让查询接口不可用。
 * 没有 HTTP 超时或连接失败处理。
 */
public class JdbcIndexRebuildRepository implements IndexRebuildRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 不在构造时碰表。 */
    public JdbcIndexRebuildRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 新建记录固定为 BUILDING。 */
    @Override
    public void create(String id, String alias, String previous, String target, String embedding, String chunking,
            Instant now) {
        jdbc.update("""
                INSERT INTO knowledge_index_rebuild(rebuild_id,source_alias,previous_index,target_index,embedding_version,chunking_version,status,created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, alias, previous, target, embedding, chunking, "BUILDING", ts(now));
    }

    /** 计数和报告齐了才能标成待激活。 */
    @Override
    public void ready(String id, long expected, long indexed, Map<String, Object> report, Instant now) {
        jdbc.update("""
                UPDATE knowledge_index_rebuild
                SET status='READY_TO_ACTIVATE',expected_chunk_count=?,indexed_chunk_count=?,validation_report_json=CAST(? AS JSON)
                WHERE rebuild_id=?
                """, expected, indexed, json(report), id);
    }

    /** 期望状态不对时更新 0 行，调用方应停止后续别名切换。 */
    @Override
    public void status(String id, String expected, String status, Instant now) {
        int changed = jdbc.update(
                "UPDATE knowledge_index_rebuild SET status=?,completed_at=? WHERE rebuild_id=? AND status=?",
                status, ts(now), id, expected);
        if (changed != 1) {
            throw new IllegalStateException("Concurrent or invalid index rebuild transition");
        }
    }

    /** 失败写入错误码和报告，并记下完成时间。 */
    @Override
    public void fail(String id, String code, Map<String, Object> report, Instant now) {
        jdbc.update("""
                UPDATE knowledge_index_rebuild
                SET status='FAILED',error_code=?,validation_report_json=CAST(? AS JSON),completed_at=?
                WHERE rebuild_id=?
                """, code, json(report), ts(now), id);
    }

    /** 找不到重建号时抛出参数异常，而不是返回空。 */
    @Override
    public IndexRebuildView find(String id) {
        List<IndexRebuildView> rows = jdbc.query("SELECT * FROM knowledge_index_rebuild WHERE rebuild_id=?",
                (rs, n) -> new IndexRebuildView(rs.getString("rebuild_id"), rs.getString("source_alias"),
                        rs.getString("previous_index"), rs.getString("target_index"),
                        rs.getString("embedding_version"), rs.getString("chunking_version"), rs.getString("status"),
                        (Long) rs.getObject("expected_chunk_count"), rs.getLong("indexed_chunk_count"),
                        read(rs.getString("validation_report_json")), rs.getString("error_code"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("completed_at"))),
                id);
        return rows.stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Index rebuild not found"));
    }

    /** 报告必须能写成 JSON，否则重建结果不能落库。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize index report", e);
        }
    }

    /** 空串或损坏 JSON 不当成成功报告，但也不让查询失败。 */
    private Map<String, Object> read(String value) {
        try {
            return value == null ? Map.of() : mapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of("parseError", true);
        }
    }

    /** JDBC 时间戳。 */
    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }

    /** 可空列保持 null。 */
    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
