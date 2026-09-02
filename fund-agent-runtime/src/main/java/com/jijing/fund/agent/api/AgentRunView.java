package com.jijing.fund.agent.api;

/** 在 Agent 运行时边界间传递 AgentRunView 数据的不可变值对象。 */
public record AgentRunView(String runId,String conversationId,String ownerUserId,String status,String executionMode,String routeReason,String planId,long lastEventSequence) {}
