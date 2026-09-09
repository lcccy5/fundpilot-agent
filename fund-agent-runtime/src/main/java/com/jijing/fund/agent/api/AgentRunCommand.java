package com.jijing.fund.agent.api;

import com.jijing.fund.agent.routing.RouteDecision;

/** Carries a request and its already-audited routing decision into the durable runtime. */
public record AgentRunCommand(String conversationId,String message,String requestId,String ownerUserId,
                              boolean hasPermission,RouteDecision routeDecision) {
    /** Compatibility constructor for non-chat callers; the coordinator routes these requests once. */
    public AgentRunCommand(String conversationId,String message,String requestId,String ownerUserId,boolean hasPermission){
        this(conversationId,message,requestId,ownerUserId,hasPermission,null);
    }
}
