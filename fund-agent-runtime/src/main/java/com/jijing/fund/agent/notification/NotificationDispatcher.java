package com.jijing.fund.agent.notification;

import java.time.Duration;
import java.time.Instant;

/**
 * 把向下穿越回撤阈值的事件变成一条通知。安静时段只占位，冷却和重复指纹都不投递。
 * 通道抛出的异常不会在这里吞掉，调用方应视为通知失败。
 */
public final class NotificationDispatcher {
    private final ThresholdNotificationService thresholds = new ThresholdNotificationService();
    private final NotificationStore store;
    private final NotificationChannel channel;

    /**
     * 绑定通知存储和投递通道。二者为 null 时，首次需要它们的调用会抛出 NullPointerException。
     */
    public NotificationDispatcher(NotificationStore store, NotificationChannel channel) {
        this.store = store;
        this.channel = channel;
    }

    /**
     * 处理一次回撤观察。未穿越或 6 小时冷却未结束时返回对应原因且不写存储。
     * 安静时段写入占位并返回 SCHEDULED_QUIET。同一小时指纹已经记录时返回 DEDUPED。
     * 否则把固定摘要交给通道；通道拒绝或失败时原样返回其结果。
     */
    public NotificationChannel.DeliveryResult onDrawdown(String ownerUserId, String ruleId, double previous, double current,
            double threshold, boolean quietHours, Instant now) {
        var decision = thresholds.evaluate(ownerUserId + "|" + ruleId, previous, current, threshold, true, Duration.ofHours(6), now, quietHours);
        String fingerprint = ruleId + "|down|" + threshold + "|" + now.toString().substring(0, 13);
        if (decision.heldForQuietHours()) {
            store.record(ownerUserId, ruleId, fingerprint, true, now);
            return new NotificationChannel.DeliveryResult(false, "SCHEDULED_QUIET");
        }
        if (!decision.shouldNotify()) {
            return new NotificationChannel.DeliveryResult(false, decision.reason());
        }
        if (!store.record(ownerUserId, ruleId, fingerprint, false, now)) {
            return new NotificationChannel.DeliveryResult(false, "DEDUPED");
        }
        return channel.deliver(new NotificationChannel.NotificationMessage(ownerUserId, "回撤超过阈值", "/portfolios"));
    }
}
