package com.jijing.fund.agent.event;

import com.jijing.fund.agent.notification.NotificationDispatcher;
import java.time.Instant;
import java.util.Set;

/**
 * 在进程内分发已提交的领域事件。
 * 只处理通知等轻量级副作用，不会在事件消费链中启动多 Agent 或计划执行流程。
 */
public final class DomainEventDispatcher {
    public static final Set<String> SUPPORTED_SCHEMAS=Set.of("v1");
    private final NotificationDispatcher notifications;
    /**
     * 创建事件分发器，并注入用于投递业务通知的分发器。
     */
    public DomainEventDispatcher(NotificationDispatcher notifications){this.notifications=notifications;}
    /**
     * 按事件类型处理已校验的领域事件。
     * 不支持的事件架构会被标记为死信；有效的回撤阈值事件会触发通知判断。
     */
    public Result dispatch(DomainEvent event,Instant now){
        if(event==null||event.schemaVersion()==null||!SUPPORTED_SCHEMAS.contains(event.schemaVersion()))
            return Result.deadLetter("UNKNOWN_SCHEMA");
        if("PORTFOLIO_DRAWDOWN_THRESHOLD_CROSSED".equals(event.eventType())&&event.ownerUserId()!=null){
            double previous=number(event.payload(),"previous",0);
            double current=number(event.payload(),"current",0);
            double threshold=number(event.payload(),"threshold",-10);
            boolean quiet=Boolean.TRUE.equals(event.payload()==null?null:event.payload().get("quietHours"));
            notifications.onDrawdown(event.ownerUserId(),"drawdown",previous,current,threshold,quiet,now);
        }
        return Result.ok();
    }
    /**
     * 从事件载荷中读取数值；字段缺失或无法转换时返回调用方提供的兜底值。
     */
    private static double number(java.util.Map<String,Object> payload,String key,double fallback){
        if(payload==null||payload.get(key)==null)return fallback;
        Object v=payload.get(key);
        if(v instanceof Number n)return n.doubleValue();
        try{return Double.parseDouble(String.valueOf(v));}catch(Exception e){return fallback;}
    }
    /**
     * 描述事件分发结果；死信结果携带不可处理的原因。
     */
    public record Result(boolean deadLetter,String reason){
        static Result ok(){return new Result(false,null);}
        static Result deadLetter(String reason){return new Result(true,reason);}
    }
}
