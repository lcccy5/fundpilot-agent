package com.jijing.fund.agent.exception;

/**
 * 有界只读运行耗尽工具预算，且允许被提升为一次持久化运行时抛出。
 * 继承执行上限异常，调用方最多提升一次；重复提升应另行拒绝。
 */
public final class AgentModeEscalationException extends AgentExecutionLimitException {

    /**
     * 记录本次为何需要提升执行模式。
     * 说明为空时仍抛出，由捕获方决定是否展示。
     */
    public AgentModeEscalationException(String message) {
        super(message);
    }
}
