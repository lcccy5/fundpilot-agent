package com.jijing.fund.agent.capability;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 实现 CapabilityExecutorRegistry 所代表的 Agent 运行时职责。 */
public final class CapabilityExecutorRegistry {
    private final Map<String, AgentCapabilityExecutor> executors;

    
    /** 执行该 Agent 运行时组件中的 CapabilityExecutorRegistry 操作。 */
    public CapabilityExecutorRegistry(Collection<? extends AgentCapabilityExecutor> executors) {
        Map<String, AgentCapabilityExecutor> values = new LinkedHashMap<>();
        for (AgentCapabilityExecutor executor : executors == null ? java.util.List.<AgentCapabilityExecutor>of() : executors) {
            if (executor == null || executor.capabilityType() == null || executor.capabilityType().isBlank()) {
                throw new IllegalArgumentException("capability executor and type are required");
            }
            if (values.putIfAbsent(executor.capabilityType(), executor) != null) {
                throw new IllegalStateException("duplicate capability executor: " + executor.capabilityType());
            }
        }
        this.executors = Map.copyOf(values);
    }

    
    /** 执行该 Agent 运行时组件中的 require 操作。 */
    public AgentCapabilityExecutor require(String capabilityType) {
        AgentCapabilityExecutor executor = executors.get(capabilityType);
        if (executor == null) executor = executors.get("*");
        if (executor == null) throw new IllegalStateException("no executor registered for capability: " + capabilityType);
        return executor;
    }

    
    /** 执行该 Agent 运行时组件中的 legacyForUnitTests 操作。 */
    public static CapabilityExecutorRegistry legacyForUnitTests() {
        return new CapabilityExecutorRegistry(java.util.List.of(new LegacyTestCapabilityExecutor()));
    }
}
