package com.jijing.fund.agent.multiagent;

/** Multi-agent stays off unless A/B shows a quality gain that justifies extra cost. */
public final class MultiAgentEnablementPolicy {
    
    /** 执行该 Agent 运行时组件中的 enable 操作。 */
    public boolean enable(double singleAgentQuality,double multiAgentQuality,double extraCostRatio){
        return multiAgentQuality-singleAgentQuality>=0.05&&extraCostRatio<=1.5;
    }
}
