package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentDisabledException 所描述的失败情形。 */
public class AgentDisabledException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentDisabledException 操作。 */
    public AgentDisabledException() { super("Fund Agent is disabled; configure a chat model and enable fund.agent.enabled"); }
}
