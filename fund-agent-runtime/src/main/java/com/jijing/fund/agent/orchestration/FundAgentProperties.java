package com.jijing.fund.agent.orchestration;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix="fund.agent")
/** 在 Agent 运行时边界间传递 FundAgentProperties 数据的不可变值对象。 */
public record FundAgentProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("fund-agent-v3") String promptVersion,
        @DefaultValue("fund-tools-v3") String toolSchemaVersion,
        @DefaultValue("6") int maxToolCallsPerRun,
        @DefaultValue("1") int maxRepeatedIdenticalToolCall,
        @DefaultValue("5") int maxModelRounds,
        @DefaultValue("30s") Duration runTimeout,
        @DefaultValue("5s") Duration toolTimeout,
        @DefaultValue("2000") int maxUserMessageChars,
        @DefaultValue("20") int maxConversationMessages,
        @DefaultValue("8000") int maxConversationTokens,
        @DefaultValue("3000") int factCardTokenBudget,
        @DefaultValue("12") int factCardMaxCount,
        @DefaultValue("24h") Duration factCardDefaultTtl) {
    
    /** 执行该 Agent 运行时组件中的 of 操作。 */
    public static FundAgentProperties of(boolean enabled,String promptVersion,String toolSchemaVersion,
            int maxToolCallsPerRun,int maxRepeatedIdenticalToolCall,int maxModelRounds,
            Duration runTimeout,Duration toolTimeout,int maxUserMessageChars,int maxConversationMessages){
        return new FundAgentProperties(enabled,promptVersion,toolSchemaVersion,maxToolCallsPerRun,maxRepeatedIdenticalToolCall,maxModelRounds,
                runTimeout,toolTimeout,maxUserMessageChars,maxConversationMessages,8000,3000,12,Duration.ofHours(24));
    }
}
