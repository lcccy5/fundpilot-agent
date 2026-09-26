package com.jijing.fund.infrastructure.notification;

import com.jijing.fund.agent.notification.NotificationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 通知记录。插入使用 {@code INSERT IGNORE}，相同指纹的重复提交返回 false，不覆盖旧行。
 * 安静期内的记录状态是 SCHEDULED，否则是 PENDING。列表最多 50 条，按创建时间倒序。
 * 没有 HTTP 超时；数据库连接失败由 Spring 抛出。
 */
@Repository
public class JdbcNotificationStore implements NotificationStore {
    private final JdbcTemplate jdbc;

    /** 不在构造时写通知。 */
    public JdbcNotificationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 影响行数大于 0 才表示这次是新通知。 */
    @Override
    public boolean record(String ownerUserId, String ruleId, String fingerprint, boolean quietHeld, Instant now) {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO notification_record(notification_id,owner_user_id,rule_id,trigger_fingerprint,status,quiet_held,created_at)
                VALUES(?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), ownerUserId, ruleId, fingerprint,
                quietHeld ? "SCHEDULED" : "PENDING", quietHeld ? 1 : 0, Timestamp.from(now));
        return inserted > 0;
    }

    /** 只返回该用户自己的记录。 */
    @Override
    public List<StoredNotification> listOwned(String ownerUserId) {
        return jdbc.query("""
                SELECT notification_id,owner_user_id,rule_id,trigger_fingerprint,status,created_at
                FROM notification_record WHERE owner_user_id=? ORDER BY created_at DESC LIMIT 50
                """, (rs, n) -> new StoredNotification(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()), ownerUserId);
    }
}
