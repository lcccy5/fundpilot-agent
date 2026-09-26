package com.jijing.fund.agent.orchestration;

/**
 * 记录本次运行使用的模型供应方和配置名，供审计而不是供路由决策。
 * 空白值会换成稳定占位，避免审计字段为空被误当成模型失败。
 * 计划、审批或对等代理失败不改变这里保存的名称。
 */
public record AgentModelDescriptor(String provider, String configuredModel) {

    /**
     * 把空白供应方记为 unknown，把空白模型名记为 configured。
     * 不访问模型，因此不会把路由顾问或对等代理的调用失败提前抛出。
     */
    public AgentModelDescriptor {
        provider = provider == null || provider.isBlank() ? "unknown" : provider;
        configuredModel = configuredModel == null || configuredModel.isBlank() ? "configured" : configuredModel;
    }
}
