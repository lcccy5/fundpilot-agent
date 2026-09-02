package com.jijing.fund.agent.notification;

import java.time.Duration;
import java.time.Instant;

/** 实现 NotificationDispatcher 所代表的 Agent 运行时职责。 */
public final class NotificationDispatcher {
    private final ThresholdNotificationService thresholds=new ThresholdNotificationService();
    private final NotificationStore store;
    private final NotificationChannel channel;
    
    /** 执行该 Agent 运行时组件中的 NotificationDispatcher 操作。 */
    public NotificationDispatcher(NotificationStore store,NotificationChannel channel){this.store=store;this.channel=channel;}
    
    /** 执行该 Agent 运行时组件中的 onDrawdown 操作。 */
    public NotificationChannel.DeliveryResult onDrawdown(String ownerUserId,String ruleId,double previous,double current,double threshold,boolean quietHours,Instant now){
        var decision=thresholds.evaluate(ownerUserId+"|"+ruleId,previous,current,threshold,true,Duration.ofHours(6),now,quietHours);
        String fingerprint=ruleId+"|down|"+threshold+"|"+now.toString().substring(0,13);
        if(decision.heldForQuietHours()){
            store.record(ownerUserId,ruleId,fingerprint,true,now);
            return new NotificationChannel.DeliveryResult(false,"SCHEDULED_QUIET");
        }
        if(!decision.shouldNotify())return new NotificationChannel.DeliveryResult(false,decision.reason());
        if(!store.record(ownerUserId,ruleId,fingerprint,false,now))return new NotificationChannel.DeliveryResult(false,"DEDUPED");
        return channel.deliver(new NotificationChannel.NotificationMessage(ownerUserId,"回撤超过阈值","/portfolios"));
    }
}
