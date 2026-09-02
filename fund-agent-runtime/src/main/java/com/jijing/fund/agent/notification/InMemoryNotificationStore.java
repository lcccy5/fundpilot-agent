package com.jijing.fund.agent.notification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 实现 InMemoryNotificationStore 所代表的 Agent 运行时职责。 */
public final class InMemoryNotificationStore implements NotificationStore {
    private final ConcurrentHashMap<String,Boolean> keys=new ConcurrentHashMap<>();
    private final List<StoredNotification> records=new ArrayList<>();
    @Override 
    /** 通过 record 操作更新持久化或内存中的运行状态。 */
    public synchronized boolean record(String ownerUserId,String ruleId,String fingerprint,boolean quietHeld,Instant now){
        boolean first=keys.putIfAbsent(ownerUserId+"|"+ruleId+"|"+fingerprint,quietHeld)==null;
        if(first)records.add(new StoredNotification(UUID.randomUUID().toString(),ownerUserId,ruleId,fingerprint,quietHeld?"SCHEDULED":"PENDING",now));
        return first;
    }
    @Override 
    /** 获取当前 Agent 操作所需的 listOwned 结果。 */
    public synchronized List<StoredNotification> listOwned(String ownerUserId){
        return records.stream().filter(r->r.ownerUserId().equals(ownerUserId)).toList();
    }
}
