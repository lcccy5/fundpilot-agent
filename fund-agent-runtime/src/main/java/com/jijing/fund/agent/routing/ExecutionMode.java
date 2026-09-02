package com.jijing.fund.agent.routing;

/** 定义 Agent 运行时使用的 ExecutionMode 可选值。 */
public enum ExecutionMode {
    DIRECT,
    DETERMINISTIC_TOOL,
    BOUNDED_REACT,
    PLAN_AND_EXECUTE,
    LEGACY_TOOL_AGENT
}
