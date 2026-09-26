package com.jijing.fund.agent.api;

import com.jijing.fund.agent.routing.RouteDecision;

/**
 * 把一次用户请求和已经审计过的路由结果交给持久化运行时。
 * 规范构造不校验空字段；消息或所有者缺失时由提交入口拒绝，而不是在这里失败。
 */
public record AgentRunCommand(
        String conversationId,
        String message,
        String requestId,
        String ownerUserId,
        boolean hasPermission,
        RouteDecision routeDecision) {

    /**
     * 为非对话调用方补齐空的路由结果，交给协调器在提交时再决定一次。
     * 不校验消息或所有者；这些空值会在提交时被拒绝。
     */
    public AgentRunCommand(
            String conversationId,
            String message,
            String requestId,
            String ownerUserId,
            boolean hasPermission) {
        this(conversationId, message, requestId, ownerUserId, hasPermission, null);
    }
}
