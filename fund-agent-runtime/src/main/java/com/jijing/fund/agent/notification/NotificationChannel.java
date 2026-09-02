package com.jijing.fund.agent.notification;

/** 定义 NotificationChannel 在 Agent 运行时中的能力契约。 */
public interface NotificationChannel {
    
    /** 执行该 Agent 运行时组件中的 deliver 操作。 */
    DeliveryResult deliver(NotificationMessage message);
    
    /** 在 Agent 运行时边界间传递 NotificationMessage 数据的不可变值对象。 */
    record NotificationMessage(String ownerUserId,String summary,String appLink){}
    
    /** 在 Agent 运行时边界间传递 DeliveryResult 数据的不可变值对象。 */
    record DeliveryResult(boolean delivered,String status){}
}
