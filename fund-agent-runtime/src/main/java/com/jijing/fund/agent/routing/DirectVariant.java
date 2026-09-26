package com.jijing.fund.agent.routing;

/**
 * 旧直接模式下的两种回答形态。
 * 现行路由器把该字段留空，不再用它表达无工具或单次检索。
 * 路由失败时不会选择其中任一值来掩盖权限、计划或审批问题。
 */
public enum DirectVariant {
    /** 历史值：不调用工具直接回答。 */
    NO_TOOL,
    /** 历史值：只做一次检索后回答。 */
    RAG_ONCE
}
