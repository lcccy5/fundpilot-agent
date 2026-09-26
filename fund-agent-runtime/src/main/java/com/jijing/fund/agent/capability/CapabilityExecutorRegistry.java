package com.jijing.fund.agent.capability;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按能力类型查找唯一执行器，供任务工作者调用。
 * 重复类型或空类型在构造时拒绝；查找失败时拒绝执行而不是静默跳过。
 */
public final class CapabilityExecutorRegistry {
    private final Map<String, AgentCapabilityExecutor> executors;

    /**
     * 按能力类型注册执行器，保留首次出现的顺序。
     * 集合为空时注册表为空；执行器或类型为空、或类型重复时拒绝构造。
     */
    public CapabilityExecutorRegistry(Collection<? extends AgentCapabilityExecutor> executors) {
        Map<String, AgentCapabilityExecutor> values = new LinkedHashMap<>();
        for (AgentCapabilityExecutor executor : executors == null ? List.<AgentCapabilityExecutor>of() : executors) {
            if (executor == null || executor.capabilityType() == null || executor.capabilityType().isBlank()) {
                throw new IllegalArgumentException("capability executor and type are required");
            }
            if (values.putIfAbsent(executor.capabilityType(), executor) != null) {
                throw new IllegalStateException("duplicate capability executor: " + executor.capabilityType());
            }
        }
        this.executors = Map.copyOf(values);
    }

    /**
     * 按类型取得执行器；没有精确匹配时才使用通配执行器。
     * 两者都不存在时抛出非法状态，调用方不得假装任务成功。
     */
    public AgentCapabilityExecutor require(String capabilityType) {
        AgentCapabilityExecutor executor = executors.get(capabilityType);
        if (executor == null) {
            executor = executors.get("*");
        }
        if (executor == null) {
            throw new IllegalStateException("no executor registered for capability: " + capabilityType);
        }
        return executor;
    }

    /**
     * 创建只包含测试通配执行器的注册表，供没有 Spring 容器的单元测试使用。
     * 不会失败；它不能代替生产注册的真实能力。
     */
    public static CapabilityExecutorRegistry legacyForUnitTests() {
        return new CapabilityExecutorRegistry(List.of(new LegacyTestCapabilityExecutor()));
    }
}
