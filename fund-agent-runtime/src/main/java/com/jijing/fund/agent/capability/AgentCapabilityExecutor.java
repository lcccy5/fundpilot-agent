package com.jijing.fund.agent.capability;

/** A server-registered capability; planner output never selects implementation classes. */
public interface AgentCapabilityExecutor {
    
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    String capabilityType();
    
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    CapabilityExecutionResult execute(CapabilityExecutionContext context);
}
