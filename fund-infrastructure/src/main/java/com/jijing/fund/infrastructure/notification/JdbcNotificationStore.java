package com.jijing.fund.infrastructure.notification;

import com.jijing.fund.agent.notification.NotificationStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationStore implements NotificationStore {
    private final JdbcTemplate jdbc;
    public JdbcNotificationStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public boolean record(String ownerUserId,String ruleId,String fingerprint,boolean quietHeld,Instant now){
        int n=jdbc.update("""
                INSERT IGNORE INTO notification_record(notification_id,owner_user_id,rule_id,trigger_fingerprint,status,quiet_held,created_at)
                VALUES(?,?,?,?,?,?,?)
                """,UUID.randomUUID().toString(),ownerUserId,ruleId,fingerprint,quietHeld?"SCHEDULED":"PENDING",quietHeld?1:0,Timestamp.from(now));
        return n>0;
    }
    @Override public List<StoredNotification> listOwned(String ownerUserId){
        return jdbc.query("""
                SELECT notification_id,owner_user_id,rule_id,trigger_fingerprint,status,created_at
                FROM notification_record WHERE owner_user_id=? ORDER BY created_at DESC LIMIT 50
                """,(rs,n)->new StoredNotification(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getTimestamp(6).toInstant()),ownerUserId);
    }
}
