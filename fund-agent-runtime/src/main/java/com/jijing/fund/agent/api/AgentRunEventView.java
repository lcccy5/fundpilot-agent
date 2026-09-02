package com.jijing.fund.agent.api;

import java.time.Instant;

/** 在 Agent 运行时边界间传递 AgentRunEventView 数据的不可变值对象。 */
public record AgentRunEventView(String eventId,long sequence,String eventType,String payloadJson,Instant createdAt) {}
