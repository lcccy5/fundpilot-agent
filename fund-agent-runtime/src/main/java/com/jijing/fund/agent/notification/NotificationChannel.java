package com.jijing.fund.agent.notification;

/**
 * 把通知交给某个投递通道。通道拒绝或失败时返回未送达结果，不把敏感持仓正文发出去。
 */
public interface NotificationChannel {
    /**
     * 投递一条通知。消息为空、摘要含有禁止外发的持仓明细，或通道故障时，delivered 为 false，并用 status 说明原因。
     */
    DeliveryResult deliver(NotificationMessage message);

    /**
     * 待投递的通知。summary 是用户可见文案，appLink 只指向应用内页面，不携带持仓明细。
     */
    record NotificationMessage(String ownerUserId, String summary, String appLink) {}

    /**
     * 投递结果。delivered 为 false 时 status 说明是被拒绝、被去重、处于安静时段，还是通道失败。
     */
    record DeliveryResult(boolean delivered, String status) {}
}
