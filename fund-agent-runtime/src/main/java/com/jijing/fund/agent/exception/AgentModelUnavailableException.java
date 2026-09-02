package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentModelUnavailableException 所描述的失败情形。 */
public class AgentModelUnavailableException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentModelUnavailableException 操作。 */
    public AgentModelUnavailableException(String message, Throwable cause) { super(message, cause); }
}
