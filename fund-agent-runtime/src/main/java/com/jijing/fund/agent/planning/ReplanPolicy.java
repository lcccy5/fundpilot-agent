package com.jijing.fund.agent.planning;

/** 实现 ReplanPolicy 所代表的 Agent 运行时职责。 */
public final class ReplanPolicy {
    private final int maxReplans;
    
    /** 执行该 Agent 运行时组件中的 ReplanPolicy 操作。 */
    public ReplanPolicy(int maxReplans){this.maxReplans=Math.max(0,maxReplans);}
    
    /** 判断 allow 对应的条件是否成立。 */
    public boolean allow(int already){return already<maxReplans;}
}
