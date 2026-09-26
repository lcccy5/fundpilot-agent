package com.jijing.fund.agent.event;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存中的事务外盒：业务动作成功后才登记待发布事件。
 * 同一消费者对同一事件只能确认一次。业务动作抛错时不会登记事件；事件本身为空时业务可能已经执行。
 */
public final class TransactionalOutbox {
    private final Map<String, OutboxRecord> events = new ConcurrentHashMap<>();
    private final Set<String> consumed = ConcurrentHashMap.newKeySet();

    /**
     * 先执行业务动作，再按去重键登记待发布事件，避免业务失败留下孤立事件。
     * 业务动作抛错时事件不会入盒；事件或去重键为空时，业务已经执行后会抛出空指针异常。
     */
    public synchronized void appendWithBusiness(Runnable business, DomainEvent event) {
        business.run();
        events.putIfAbsent(event.deduplicationKey(), new OutboxRecord(event, "PENDING", 0));
    }

    /**
     * 领取一条待发布或等待重试的事件，并把状态改成发布中。
     * 没有可领取事件时返回空。消费者和当前时间在内存实现中不参与筛选，也不会占用租约。
     */
    public synchronized Optional<DomainEvent> claimPending(String consumer, Instant now) {
        for (OutboxRecord rec : events.values()) {
            if (!"PENDING".equals(rec.status) && !"RETRY_WAIT".equals(rec.status)) {
                continue;
            }
            rec.status = "PUBLISHING";
            return Optional.of(rec.event);
        }
        return Optional.empty();
    }

    /**
     * 以消费者和事件标识作为幂等键确认消费。
     * 重复确认返回 false；首次确认把匹配事件标成已发布。找不到事件时仍返回 true，但没有任何记录变成已发布。
     */
    public synchronized boolean consumeIdempotent(String consumer, String eventId) {
        String key = consumer + "|" + eventId;
        if (!consumed.add(key)) {
            return false;
        }
        for (OutboxRecord rec : events.values()) {
            if (rec.event.eventId().equals(eventId)) {
                rec.status = "PUBLISHED";
            }
        }
        return true;
    }

    /**
     * 返回当前登记的事件数量，含尚未发布和已发布的记录。
     * 不会失败。
     */
    public int size() {
        return events.size();
    }

    /**
     * 判断指定事件是否已经成功发布。
     * 标识不存在时返回 false，不抛出找不到事件的异常。
     */
    public boolean published(String eventId) {
        return events.values().stream()
                .anyMatch(r -> r.event.eventId().equals(eventId) && "PUBLISHED".equals(r.status));
    }

    /**
     * 外盒中的一条可变记录，保存事件、发布状态和预留的尝试次数。
     * 尝试次数字段目前不会递增，失败重试不会体现在这条记录上。
     */
    private static final class OutboxRecord {
        final DomainEvent event;
        String status;
        int attempts;

        /**
         * 创建一条内部记录。
         * 不校验事件是否为空；空事件会在后续读取标识时失败。
         */
        OutboxRecord(DomainEvent event, String status, int attempts) {
            this.event = event;
            this.status = status;
            this.attempts = attempts;
        }
    }
}
