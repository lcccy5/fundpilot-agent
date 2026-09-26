package com.jijing.fund.agent.notification;

import java.time.Instant;
import java.util.List;

/**
 * 按所有者和指纹保存通知，避免同一规则在同一时间窗重复投递。
 * 存储失败应由实现抛出异常，调用方不能把未记录的通知当成已去重。
 */
public interface NotificationStore {
    /**
     * 已记录的一条通知。status 为 SCHEDULED 表示只因安静时段占位，尚未投递。
     */
    record StoredNotification(String notificationId, String ownerUserId, String ruleId, String fingerprint, String status, Instant createdAt) {}

    /**
     * 第一次见到该指纹时写入并返回 true。同一所有者、规则和指纹再次出现时返回 false，不覆盖原状态。
     * quietHeld 为 true 时状态记为待安静时段结束后处理，而不是立即投递。
     */
    boolean record(String ownerUserId, String ruleId, String fingerprint, boolean quietHeld, Instant now);

    /**
     * 返回该所有者的通知。其他所有者的记录不会出现；没有记录时返回空列表。
     */
    List<StoredNotification> listOwned(String ownerUserId);
}
