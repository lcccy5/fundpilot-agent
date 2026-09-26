package com.jijing.fund.agent.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 跨轮次解析基金和区间指代时使用的短状态。
 * 基金或区间为空表示当前还没有可延续的指代，不视为损坏。
 */
public record AgentConversationState(
        String conversationId,
        String activeFund,
        List<String> mentionedFunds,
        LocalDate periodStart,
        LocalDate periodEnd,
        String activeTopic,
        Instant updatedAt) {

    /**
     * 把空的已提及基金列表收成不可变空列表。
     * 不校验区间开始是否早于结束；颠倒的日期会原样保留。
     */
    public AgentConversationState {
        mentionedFunds = mentionedFunds == null ? List.of() : List.copyOf(mentionedFunds);
    }

    /**
     * 构造一份没有任何基金、区间或话题的初始状态，更新时间固定为纪元。
     * 不校验会话标识；空标识会原样写入。
     */
    public static AgentConversationState empty(String conversationId) {
        return new AgentConversationState(conversationId, null, List.of(), null, null, null, Instant.EPOCH);
    }
}
