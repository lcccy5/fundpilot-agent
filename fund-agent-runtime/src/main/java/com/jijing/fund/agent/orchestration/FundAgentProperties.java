package com.jijing.fund.agent.orchestration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 运行的预算、超时和提示词版本配置。
 * 工具次数、重复调用和消息长度超限时由执行追踪或入口校验拒绝，而不是放宽配置。
 * 本配置不决定路由模式、审批结果或对等代理是否启动。
 */
@ConfigurationProperties(prefix = "fund.agent")
public record FundAgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("fund-agent-v3") String promptVersion,
        @DefaultValue("fund-tools-v3") String toolSchemaVersion,
        @DefaultValue("4") int maxToolCallsPerRun,
        @DefaultValue("1") int maxRepeatedIdenticalToolCall,
        @DefaultValue("5") int maxModelRounds,
        @DefaultValue("60s") Duration runTimeout,
        @DefaultValue("15s") Duration toolTimeout,
        @DefaultValue("2000") int maxUserMessageChars,
        @DefaultValue("4") int maxConversationMessages,
        @DefaultValue("3000") int maxConversationTokens,
        @DefaultValue("1200") int factCardTokenBudget,
        @DefaultValue("3") int factCardMaxCount,
        @DefaultValue("24h") Duration factCardDefaultTtl) {

    /**
     * 用显式的运行预算构造配置，记忆与事实卡预算采用内置默认值。
     * 不校验数值是否为正；非正预算会在记忆或执行追踪使用时失败，而不是在这里静默改写。
     */
    public static FundAgentProperties of(boolean enabled, String promptVersion, String toolSchemaVersion,
            int maxToolCallsPerRun, int maxRepeatedIdenticalToolCall, int maxModelRounds,
            Duration runTimeout, Duration toolTimeout, int maxUserMessageChars, int maxConversationMessages) {
        return new FundAgentProperties(enabled, promptVersion, toolSchemaVersion, maxToolCallsPerRun,
                maxRepeatedIdenticalToolCall, maxModelRounds, runTimeout, toolTimeout, maxUserMessageChars,
                maxConversationMessages, 3000, 1200, 3, Duration.ofHours(24));
    }
}
