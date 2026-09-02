package com.jijing.fund.agent.notification;

import java.util.ArrayList;
import java.util.List;

/** 实现 InAppNotificationChannel 所代表的 Agent 运行时职责。 */
public final class InAppNotificationChannel implements NotificationChannel {
    private final List<NotificationMessage> inbox=new ArrayList<>();
    @Override 
    /** 执行该 Agent 运行时组件中的 deliver 操作。 */
    public DeliveryResult deliver(NotificationMessage message){
        if(message==null||message.summary()==null||message.summary().contains("持仓明细"))return new DeliveryResult(false,"REJECTED_SENSITIVE");
        inbox.add(message);
        return new DeliveryResult(true,"INBOX");
    }
    
    /** 执行该 Agent 运行时组件中的 inbox 操作。 */
    public List<NotificationMessage> inbox(){return List.copyOf(inbox);}
}
