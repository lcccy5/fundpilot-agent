package com.jijing.fund.agent.notification;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用内通知通道。敏感摘要会被拒绝，不会进入收件箱。
 */
public final class InAppNotificationChannel implements NotificationChannel {
    private final List<NotificationMessage> inbox = new ArrayList<>();

    /**
     * 接受普通摘要并放入收件箱。消息为 null，或摘要包含“持仓明细”时返回 REJECTED_SENSITIVE，不保存消息。
     */
    @Override
    public DeliveryResult deliver(NotificationMessage message) {
        if (message == null || message.summary() == null || message.summary().contains("持仓明细")) {
            return new DeliveryResult(false, "REJECTED_SENSITIVE");
        }
        inbox.add(message);
        return new DeliveryResult(true, "INBOX");
    }

    /**
     * 返回当前收件箱的副本。调用方修改返回列表不会改变通道里的记录。
     */
    public List<NotificationMessage> inbox() {
        return List.copyOf(inbox);
    }
}
