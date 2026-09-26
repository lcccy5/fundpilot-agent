package com.jijing.fund.agent.api;

/**
 * 一次模型调用的提示词、补全和总 token 数。
 * 三项都可以为空，表示计量方没有回传，不把空值当成 0。
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    /**
     * 返回三项都为空的用量，表示本次没有可用计量。
     * 不会失败。
     */
    public static TokenUsage empty() {
        return new TokenUsage(null, null, null);
    }
}
