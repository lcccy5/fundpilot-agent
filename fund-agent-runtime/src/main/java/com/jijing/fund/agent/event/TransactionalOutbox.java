package com.jijing.fund.agent.event;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提供内存版事务 Outbox，确保业务动作成功后才登记待发布的领域事件。
 * 同一消费者对同一事件只能成功消费一次。
 */
public final class TransactionalOutbox {
    private final Map<String,OutboxRecord> events=new ConcurrentHashMap<>();
    private final Set<String> consumed=ConcurrentHashMap.newKeySet();

    /**
     * 先执行业务动作，再按去重键登记待发布事件，避免业务失败时留下孤立事件。
     */
    public synchronized void appendWithBusiness(Runnable business,DomainEvent event){
        business.run();
        events.putIfAbsent(event.deduplicationKey(),new OutboxRecord(event,"PENDING",0));
    }

    /**
     * 领取一条待发布或待重试事件，并将其状态切换为发布中。
     * 当前实现为内存队列，消费者和时间参数保留给持久化实现的租约语义。
     */
    public synchronized Optional<DomainEvent> claimPending(String consumer,Instant now){
        for(OutboxRecord rec:events.values()){
            if(!"PENDING".equals(rec.status)&&!"RETRY_WAIT".equals(rec.status))continue;
            rec.status="PUBLISHING";
            return Optional.of(rec.event);
        }
        return Optional.empty();
    }

    /**
     * 以消费者和事件 ID 作为幂等键确认消费结果。
     * 重复确认返回 {@code false}，首次确认会将对应事件标记为已发布。
     */
    public synchronized boolean consumeIdempotent(String consumer,String eventId){
        String key=consumer+"|"+eventId;
        if(!consumed.add(key))return false;
        for(OutboxRecord rec:events.values())if(rec.event.eventId().equals(eventId))rec.status="PUBLISHED";
        return true;
    }

    /**
     * 返回当前 Outbox 中登记的事件总数。
     */
    public int size(){return events.size();}
    /**
     * 判断指定事件是否已经被成功发布。
     */
    public boolean published(String eventId){return events.values().stream().anyMatch(r->r.event.eventId().equals(eventId)&&"PUBLISHED".equals(r.status));}

    /**
     * 保存 Outbox 事件及其发布状态的内部可变记录。
     */
    private static final class OutboxRecord {
        final DomainEvent event;String status;int attempts;
        /**
         * 创建内部 Outbox 记录。
         */
        OutboxRecord(DomainEvent event,String status,int attempts){this.event=event;this.status=status;this.attempts=attempts;}
    }
}
