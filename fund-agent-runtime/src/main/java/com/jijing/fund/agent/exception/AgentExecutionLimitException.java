package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentExecutionLimitException 所描述的失败情形。 */
public class AgentExecutionLimitException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentExecutionLimitException 操作。 */
    public AgentExecutionLimitException(String message) { super(message); }
}
