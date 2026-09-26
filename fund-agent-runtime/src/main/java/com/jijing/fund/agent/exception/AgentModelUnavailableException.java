package com.jijing.fund.agent.exception;

/**
 * 对话模型不可用或调用失败时抛出，供上层把运行标成失败。
 * 保留原始原因，避免把供应商细节直接当成用户可见答案。
 */
public class AgentModelUnavailableException extends RuntimeException {

    /**
     * 用安全说明和原始失败原因构造异常。
     * 原因为空时仍可抛出，只是没有可追查的下层异常。
     */
    public AgentModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
