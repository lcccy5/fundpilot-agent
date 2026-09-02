package com.jijing.fund.agent.orchestration;

/** 在 Agent 运行时边界间传递 AgentModelDescriptor 数据的不可变值对象。 */
public record AgentModelDescriptor(String provider, String configuredModel) {
    public AgentModelDescriptor {
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        configuredModel = configuredModel == null || configuredModel.isBlank() ? "configured" : configuredModel;
    }
}
