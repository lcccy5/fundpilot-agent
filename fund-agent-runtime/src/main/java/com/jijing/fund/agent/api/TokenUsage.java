package com.jijing.fund.agent.api;

/** 在 Agent 运行时边界间传递 TokenUsage 数据的不可变值对象。 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    
    /** 执行该 Agent 运行时组件中的 empty 操作。 */
    public static TokenUsage empty() { return new TokenUsage(null, null, null); }
}
