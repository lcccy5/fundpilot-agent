package com.jijing.fund.agent.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 实现 ThresholdNotificationService 所代表的 Agent 运行时职责。 */
public final class ThresholdNotificationService {
    
    /** 在 Agent 运行时边界间传递 Decision 数据的不可变值对象。 */
    public record Decision(boolean shouldNotify,boolean heldForQuietHours,String reason){}
    private final Map<String,Instant> lastFired=new ConcurrentHashMap<>();
    private final Map<String,Double> lastValue=new ConcurrentHashMap<>();

    
    /** 执行该 Agent 运行时组件中的 evaluate 操作。 */
    public Decision evaluate(String ownerRuleKey,double previous,double current,double threshold,boolean crossingMustBeDownward,Duration cooldown,Instant now,boolean quietHours){
        boolean crossed=crossingMustBeDownward?previous>threshold&&current<=threshold:previous<threshold&&current>=threshold;
        if(!crossed)return new Decision(false,false,"NO_CROSSING");
        Instant last=lastFired.get(ownerRuleKey);
        if(last!=null&&now.isBefore(last.plus(cooldown)))return new Decision(false,false,"COOLDOWN");
        if(quietHours)return new Decision(false,true,"QUIET_HOURS_HELD");
        lastFired.put(ownerRuleKey,now);
        lastValue.put(ownerRuleKey,current);
        return new Decision(true,false,"FIRED");
    }
}
