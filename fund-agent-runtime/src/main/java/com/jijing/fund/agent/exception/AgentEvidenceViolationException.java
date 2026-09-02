package com.jijing.fund.agent.exception;

/** 表示 Agent 运行时发生 AgentEvidenceViolationException 所描述的失败情形。 */
public class AgentEvidenceViolationException extends RuntimeException {
    
    /** 执行该 Agent 运行时组件中的 AgentEvidenceViolationException 操作。 */
    public AgentEvidenceViolationException(String message){super(message);}
}
