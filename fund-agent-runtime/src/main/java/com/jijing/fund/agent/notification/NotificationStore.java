package com.jijing.fund.agent.notification;

import java.time.Instant;
import java.util.List;

/** 定义 NotificationStore 在 Agent 运行时中的能力契约。 */
public interface NotificationStore {
    
    /** 在 Agent 运行时边界间传递 StoredNotification 数据的不可变值对象。 */
    record StoredNotification(String notificationId,String ownerUserId,String ruleId,String fingerprint,String status,Instant createdAt){}
    /** @return true if this fingerprint was recorded for the first time */
    boolean record(String ownerUserId,String ruleId,String fingerprint,boolean quietHeld,Instant now);
    
    /** 获取当前 Agent 操作所需的 listOwned 结果。 */
    List<StoredNotification> listOwned(String ownerUserId);
}
