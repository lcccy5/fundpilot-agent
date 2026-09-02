package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentPolicyViolationException 所描述的失败情形。 */
public class AgentPolicyViolationException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentPolicyViolationException 操作。 */
    public AgentPolicyViolationException(String message) {
        super(message);
    }
}
