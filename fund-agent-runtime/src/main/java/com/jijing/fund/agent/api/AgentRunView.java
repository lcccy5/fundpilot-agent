package com.jijing.fund.agent.api;

/**
 * 一次持久化运行的只读快照，包含状态、路由原因和最新事件序号。
 * 计划尚未创建时计划标识为空；本类型不因此失败。
 */
public record AgentRunView(
        String runId,
        String conversationId,
        String ownerUserId,
        String status,
        String executionMode,
        String routeReason,
        String planId,
        long lastEventSequence) {
}
