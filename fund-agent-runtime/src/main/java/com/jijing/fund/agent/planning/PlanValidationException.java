package com.jijing.fund.agent.planning;
/** 表示 Agent 运行时发生 PlanValidationException 所描述的失败情形。 */
public class PlanValidationException extends RuntimeException {
    
    /** 获取当前 Agent 操作所需的 PlanValidationException 结果。 */
    public PlanValidationException(String message){super(message);}
}
