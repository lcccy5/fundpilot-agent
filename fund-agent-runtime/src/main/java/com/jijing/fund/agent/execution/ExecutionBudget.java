package com.jijing.fund.agent.execution;

import java.util.concurrent.atomic.AtomicInteger;

/** 实现 ExecutionBudget 所代表的 Agent 运行时职责。 */
public final class ExecutionBudget {
    private final AtomicInteger remainingTools;
    private final AtomicInteger remainingTokens;
    
    /** 执行该 Agent 运行时组件中的 ExecutionBudget 操作。 */
    public ExecutionBudget(int maxTools,int maxTokens){
        this.remainingTools=new AtomicInteger(Math.max(0,maxTools));
        this.remainingTokens=new AtomicInteger(Math.max(0,maxTokens));
    }
    
    /** 执行 consumeTools 操作，并应用相应的 Agent 运行时状态变化。 */
    public boolean consumeTools(int n){
        while(true){
            int cur=remainingTools.get();
            if(cur<n)return false;
            if(remainingTools.compareAndSet(cur,cur-n))return true;
        }
    }
    
    /** 执行 consumeTokens 操作，并应用相应的 Agent 运行时状态变化。 */
    public boolean consumeTokens(int n){
        while(true){
            int cur=remainingTokens.get();
            if(cur<n)return false;
            if(remainingTokens.compareAndSet(cur,cur-n))return true;
        }
    }
    
    /** 执行该 Agent 运行时组件中的 remainingTools 操作。 */
    public int remainingTools(){return remainingTools.get();}
    
    /** 执行该 Agent 运行时组件中的 remainingTokens 操作。 */
    public int remainingTokens(){return remainingTokens.get();}
}
