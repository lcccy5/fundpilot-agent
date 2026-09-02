package com.jijing.fund.agent.api;

import java.time.Instant;

/** 在 Agent 运行时边界间传递 ConversationResult 数据的不可变值对象。 */
public record ConversationResult(String conversationId, Instant createdAt) {}
