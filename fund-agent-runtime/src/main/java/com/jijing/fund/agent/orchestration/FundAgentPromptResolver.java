package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.FundAgentRequest;

/** Resolves a prompt for every request so canary and evaluation versions are never startup singletons. */
@FunctionalInterface
/** 定义 FundAgentPromptResolver 在 Agent 运行时中的能力契约。 */
public interface FundAgentPromptResolver {
    /** Resolves the safe prompt and its release metadata for one Agent request. */
    ResolvedFundAgentPrompt resolve(FundAgentRequest request);
}
