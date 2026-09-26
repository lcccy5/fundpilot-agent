package com.jijing.fund.agent.exception;

/**
 * 轮次、工具或令牌预算被耗尽，当前执行必须停止。
 * 是否允许提升为更重的执行模式，由子类或调用方另行决定。
 */
public class AgentExecutionLimitException extends RuntimeException {

    /**
     * 记录触达的执行上限。
     * 说明为空时仍抛出，调用方只能看到空消息。
     */
    public AgentExecutionLimitException(String message) {
        super(message);
    }
}
