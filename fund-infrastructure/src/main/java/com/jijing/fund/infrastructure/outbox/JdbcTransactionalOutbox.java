package com.jijing.fund.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.event.DomainEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事务性发件箱。追加使用 {@code INSERT IGNORE}，相同事件编号的重复提交被数据库丢掉，Java 不看影响行数。
 * 消费也用 {@code INSERT IGNORE}：插入 0 行表示该消费者已经处理过，返回 false，且不把事件标成已发布。
 * 载荷或证据序列化失败时写成空数组 {@code []}；读回失败时分别变成空 Map 或空列表。
 * 领取后的租约是 30 秒。没有 HTTP 超时；数据库连接失败由 Spring 抛出。
 */
@Repository
public class JdbcTransactionalOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 不在构造时扫表。 */
    public JdbcTransactionalOutbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 业务动作和事件追加在同一事务里，业务抛错则事件不会留下。 */
    @Transactional
    public void appendWithBusiness(Runnable business, DomainEvent event) {
        business.run();
        append(event);
    }

    /** 重复事件编号被忽略，调用方无法从返回值得知是否新插入。 */
    @Transactional
    public void append(DomainEvent event) {
        jdbc.update("""
                INSERT IGNORE INTO outbox_event(event_id,event_type,aggregate_type,aggregate_id,owner_user_id,schema_version,payload_json,evidence_ids_json,deduplication_key,status,attempts,occurred_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,0,?)
                """, event.eventId(), event.eventType(), event.aggregateType(), event.aggregateId(),
                event.ownerUserId(), event.schemaVersion(), json(event.payload()), json(event.evidenceIds()),
                event.deduplicationKey(), "PENDING", Timestamp.from(event.occurredAt()));
    }

    /**
     * 跳过仍在租约内的行，并用 SKIP LOCKED 避免两个工人阻塞。没有可领事件时为空。
     * 领到后状态改为发布中，尝试次数加一。
     */
    @Transactional
    public Optional<DomainEvent> claimPending(String workerId, Instant now) {
        List<DomainEvent> rows = jdbc.query("""
                SELECT event_id,event_type,aggregate_type,aggregate_id,owner_user_id,schema_version,CAST(payload_json AS CHAR),CAST(evidence_ids_json AS CHAR),deduplication_key,occurred_at
                FROM outbox_event WHERE status IN ('PENDING','RETRY_WAIT') AND (lease_until IS NULL OR lease_until<?)
                ORDER BY occurred_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, n) -> new DomainEvent(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(10).toInstant(), rs.getString(6), rs.getString(9),
                readMap(rs.getString(7)), readList(rs.getString(8)), null), Timestamp.from(now));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        DomainEvent event = rows.getFirst();
        jdbc.update("""
                UPDATE outbox_event SET status='PUBLISHING',lease_owner=?,lease_until=?,attempts=attempts+1
                WHERE event_id=?
                """, workerId, Timestamp.from(now.plusSeconds(30)), event.eventId());
        return Optional.of(event);
    }

    /**
     * 同一消费者对同一事件只记一次。重复消费返回 false，事件状态保持原样。
     */
    @Transactional
    public boolean consumeIdempotent(String consumer, String eventId, Instant now) {
        int inserted = jdbc.update(
                "INSERT IGNORE INTO event_consumption(consumer_name,event_id,status,consumed_at) VALUES(?,?,?,?)",
                consumer, eventId, "CONSUMED", Timestamp.from(now));
        if (inserted == 0) {
            return false;
        }
        jdbc.update("UPDATE outbox_event SET status='PUBLISHED',published_at=? WHERE event_id=?",
                Timestamp.from(now), eventId);
        return true;
    }

    /** 死信原因空缺时写成 UNKNOWN。事件状态改为 DEAD_LETTER。 */
    @Transactional
    public void deadLetter(String eventId, String reason, Instant now) {
        jdbc.update("""
                INSERT INTO event_dead_letter(dead_letter_id,event_id,reason_code,detail,created_at)
                VALUES(?,?,?,?,?)
                """, UUID.randomUUID().toString(), eventId, reason == null ? "UNKNOWN" : reason, null,
                Timestamp.from(now));
        jdbc.update("UPDATE outbox_event SET status='DEAD_LETTER',last_error_code=? WHERE event_id=?",
                reason, eventId);
    }

    /** null 写成空数组。序列化失败也退回空数组，避免发件箱因为坏载荷完全写不进去。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 空正文或坏 JSON 当成没有载荷。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String raw) {
        try {
            return raw == null || raw.isBlank() ? Map.of() : mapper.readValue(raw, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** 空正文或坏 JSON 当成没有证据编号。 */
    @SuppressWarnings("unchecked")
    private List<String> readList(String raw) {
        try {
            return raw == null || raw.isBlank() ? List.of() : mapper.readValue(raw, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
