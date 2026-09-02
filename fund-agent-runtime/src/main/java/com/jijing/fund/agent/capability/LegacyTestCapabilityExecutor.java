package com.jijing.fund.agent.capability;

import java.util.List;

/** Compatibility-only executor for unit tests that construct PlanTaskWorker without Spring beans. */
final class LegacyTestCapabilityExecutor implements AgentCapabilityExecutor {
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "*"; }
    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        String taskKey = context.task().taskKey();
        return new CapabilityExecutionResult("artifact://" + context.task().planId() + "/" + taskKey,
                List.of("ev-" + taskKey));
    }
}
