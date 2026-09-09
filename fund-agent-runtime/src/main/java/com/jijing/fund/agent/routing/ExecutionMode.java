package com.jijing.fund.agent.routing;

/** 定义 Agent 运行时使用的 ExecutionMode 可选值。 */
public enum ExecutionMode {
    BOUNDED_REACT,
    PLAN_AND_EXECUTE,
    /** Historical persisted values retained for backward-compatible reads. */
    @Deprecated DIRECT,
    @Deprecated DETERMINISTIC_TOOL,
    /** Historical persisted value retained for backward-compatible reads. */
    @Deprecated
    LEGACY_TOOL_AGENT
}
