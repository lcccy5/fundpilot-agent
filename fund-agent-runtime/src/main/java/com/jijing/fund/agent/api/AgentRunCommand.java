package com.jijing.fund.agent.api;

/** 在 Agent 运行时边界间传递 AgentRunCommand 数据的不可变值对象。 */
public record AgentRunCommand(String conversationId,String message,String requestId,String ownerUserId,boolean hasPermission) {}
