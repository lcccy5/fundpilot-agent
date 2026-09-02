package com.jijing.fund.agent.orchestration;

/** Request-level prompt content plus AgentOps release metadata. */
public record ResolvedFundAgentPrompt(String version, String content, String sha256,
                                      String releaseId, String variant) {
    /** Converts the existing local prompt into a stable resolution. */
    public static ResolvedFundAgentPrompt local(FundAgentPrompt prompt) {
        return new ResolvedFundAgentPrompt(prompt.version(), prompt.content(), prompt.sha256(), null, "stable");
    }
}
