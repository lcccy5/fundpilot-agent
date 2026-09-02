package com.jijing.fund.agent.api;

import com.jijing.fund.domain.identity.AuthenticatedUser;

/** 在 Agent 运行时边界间传递 FundAgentRequest 数据的不可变值对象。 */
public record FundAgentRequest(String conversationId, String message, String requestId, AuthenticatedUser actor,
                               String forcedPromptVersion) {
    /** Creates a normal authenticated request without forcing an evaluation prompt. */
    public FundAgentRequest(String conversationId,String message,String requestId,AuthenticatedUser actor){this(conversationId,message,requestId,actor,null);}
    /** Creates a compatibility request without an authenticated actor. */
    public FundAgentRequest(String conversationId,String message,String requestId){this(conversationId,message,requestId,null,null);}
}
