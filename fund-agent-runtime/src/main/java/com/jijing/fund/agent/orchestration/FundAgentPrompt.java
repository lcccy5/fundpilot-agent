package com.jijing.fund.agent.orchestration;

/** 在 Agent 运行时边界间传递 FundAgentPrompt 数据的不可变值对象。 */
public record FundAgentPrompt(String version, String content, String sha256) {
    public FundAgentPrompt {
        if (version == null || version.isBlank() || content == null || content.isBlank()) {
            throw new IllegalArgumentException("Agent prompt version and content are required");
        }
    }
}
