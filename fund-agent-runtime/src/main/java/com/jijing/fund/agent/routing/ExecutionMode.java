package com.jijing.fund.agent.routing;

/**
 * 一次用户请求允许进入的执行模式。
 * 现行决策只有有界 ReAct 和计划执行。历史持久化值只为读取旧记录保留，路由器不再产出它们。
 * 权限缺失时路由直接拒绝，而不是退回已废弃的直接模式。计划校验失败、审批拒绝或对等代理失败不改变枚举值。
 */
public enum ExecutionMode {
    /** 在交互时限和工具预算内完成，预算耗尽时由运行时决定是否升级。 */
    BOUNDED_REACT,
    /** 交给持久化任务图执行，适用于多阶段、报告、审批或后台请求。 */
    PLAN_AND_EXECUTE,
    /** 历史持久化值，只用于兼容读取。新的路由失败不得再写成该模式。 */
    @Deprecated
    DIRECT,
    /** 历史持久化值，只用于兼容读取。未知请求不会落到该模式。 */
    @Deprecated
    DETERMINISTIC_TOOL,
    /** 历史持久化值，只用于兼容读取。对等代理失败不会回退到该模式。 */
    @Deprecated
    LEGACY_TOOL_AGENT
}
