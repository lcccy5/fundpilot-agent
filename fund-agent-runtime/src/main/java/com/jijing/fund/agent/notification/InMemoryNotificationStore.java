package com.jijing.fund.agent.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内通知存储。相同指纹只保留第一次写入，后续重复调用返回 false，避免重复提醒。
 */
public final class InMemoryNotificationStore implements NotificationStore {
    private final ConcurrentHashMap<String, Boolean> keys = new ConcurrentHashMap<>();
    private final List<StoredNotification> records = new ArrayList<>();

    /**
     * 按所有者、规则和指纹去重。首次写入时安静占位记为 SCHEDULED，否则记为 PENDING。
     * 重复指纹返回 false，不更新已有记录的状态。
     */
    @Override
    public synchronized boolean record(String ownerUserId, String ruleId, String fingerprint, boolean quietHeld, Instant now) {
        boolean first = keys.putIfAbsent(ownerUserId + "|" + ruleId + "|" + fingerprint, quietHeld) == null;
        if (first) {
            records.add(new StoredNotification(UUID.randomUUID().toString(), ownerUserId, ruleId, fingerprint,
                    quietHeld ? "SCHEDULED" : "PENDING", now));
        }
        return first;
    }

    /**
     * 只返回该所有者的记录。所有者标识不匹配的通知不会泄露；没有记录时返回空列表。
     */
    @Override
    public synchronized List<StoredNotification> listOwned(String ownerUserId) {
        return records.stream().filter(r -> r.ownerUserId().equals(ownerUserId)).toList();
    }
}
