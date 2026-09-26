package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.FundAgentRequest;

/**
 * 为每一次请求解析提示词，避免金丝雀或评估版本被固化成启动期单例。
 * 强制版本无法解析时实现应失败关闭，而不是悄悄换成另一份提示词。
 * 计划校验、路由、审批或对等代理失败不改变提示词选择。
 */
@FunctionalInterface
public interface FundAgentPromptResolver {

    /**
     * 解析该请求应使用的提示词和发布信息。
     * 远程解析失败且请求强制指定版本时抛出异常，本次运行不得开始。
     * 未强制版本时允许回退到本地稳定提示词。
     */
    ResolvedFundAgentPrompt resolve(FundAgentRequest request);
}
