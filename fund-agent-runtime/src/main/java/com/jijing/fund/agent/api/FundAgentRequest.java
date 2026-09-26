package com.jijing.fund.agent.api;

import com.jijing.fund.domain.identity.AuthenticatedUser;

/**
 * 一次对话请求。可以强制使用某个评测提示词版本，也可以不带登录主体。
 * 本类型不拒绝空消息；缺少主体或消息是否可执行由对话用例决定。
 */
public record FundAgentRequest(
        String conversationId,
        String message,
        String requestId,
        AuthenticatedUser actor,
        String forcedPromptVersion) {

    /**
     * 构造一次普通的已登录请求，不强制评测提示词。
     * 不校验会话、消息或主体；空主体会原样保留。
     */
    public FundAgentRequest(String conversationId, String message, String requestId, AuthenticatedUser actor) {
        this(conversationId, message, requestId, actor, null);
    }

    /**
     * 构造一次没有登录主体的兼容请求。
     * 主体固定为空，后续鉴权失败由对话实现抛出，这里不会提前拒绝。
     */
    public FundAgentRequest(String conversationId, String message, String requestId) {
        this(conversationId, message, requestId, null, null);
    }
}
