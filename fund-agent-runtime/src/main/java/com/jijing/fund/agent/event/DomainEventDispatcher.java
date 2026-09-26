package com.jijing.fund.agent.event;

import com.jijing.fund.agent.notification.NotificationDispatcher;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 在进程内分发已经提交的领域事件。
 * 只处理通知一类轻量副作用，不会在消费链里启动多智能体或计划执行。
 */
public final class DomainEventDispatcher {
    public static final Set<String> SUPPORTED_SCHEMAS = Set.of("v1");
    private final NotificationDispatcher notifications;

    /**
     * 创建分发器并注入通知出口。
     * 通知出口为空时，构造本身成功，但处理回撤事件时会失败。
     */
    public DomainEventDispatcher(NotificationDispatcher notifications) {
        this.notifications = notifications;
    }

    /**
     * 按事件类型处理一条领域事件。
     * 事件为空、架构版本为空或不在支持列表中时返回死信，不抛异常；回撤事件缺少所有者时直接忽略。
     */
    public Result dispatch(DomainEvent event, Instant now) {
        if (event == null || event.schemaVersion() == null || !SUPPORTED_SCHEMAS.contains(event.schemaVersion())) {
            return Result.deadLetter("UNKNOWN_SCHEMA");
        }
        if ("PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED".equals(event.eventType()) && event.ownerUserId() != null) {
            double previous = number(event.payload(), "previous", 0);
            double current = number(event.payload(), "current", 0);
            double threshold = number(event.payload(), "threshold", -10);
            boolean quiet = Boolean.TRUE.equals(event.payload() == null ? null : event.payload().get("quietHours"));
            notifications.onDrawdown(event.ownerUserId(), "drawdown", previous, current, threshold, quiet, now);
        }
        return Result.ok();
    }

    /**
     * 从载荷读取数值；字段缺失、载荷为空或无法转换时返回兜底值。
     * 不抛出数字格式异常。
     */
    private static double number(Map<String, Object> payload, String key, double fallback) {
        if (payload == null || payload.get(key) == null) {
            return fallback;
        }
        Object v = payload.get(key);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 分发结果。死信时携带不可处理的原因，成功时原因为空。
     * 不使用该结果触发重试。
     */
    public record Result(boolean deadLetter, String reason) {

        /**
         * 表示事件已接受，没有死信原因。
         * 不会失败。
         */
        static Result ok() {
            return new Result(false, null);
        }

        /**
         * 表示事件无法处理，并记录原因。
         * 原因为空时仍然是死信，只是没有说明。
         */
        static Result deadLetter(String reason) {
            return new Result(true, reason);
        }
    }
}
