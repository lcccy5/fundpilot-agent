package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentRunNotFoundException 所描述的失败情形。 */
public class AgentRunNotFoundException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentRunNotFoundException 操作。 */
    public AgentRunNotFoundException(String message){super(message);}
}
