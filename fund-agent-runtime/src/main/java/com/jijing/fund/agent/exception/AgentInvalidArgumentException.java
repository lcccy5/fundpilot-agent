package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentInvalidArgumentException 所描述的失败情形。 */
public class AgentInvalidArgumentException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentInvalidArgumentException 操作。 */
    public AgentInvalidArgumentException(String message) { super(message); }
}
