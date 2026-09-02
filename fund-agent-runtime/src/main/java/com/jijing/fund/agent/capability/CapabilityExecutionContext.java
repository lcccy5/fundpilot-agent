package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;

/** 在 Agent 运行时边界间传递 CapabilityExecutionContext 数据的不可变值对象。 */
public record CapabilityExecutionContext(ClaimedTask task) {
    public CapabilityExecutionContext {
        if (task == null) throw new IllegalArgumentException("task is required");
    }
}
