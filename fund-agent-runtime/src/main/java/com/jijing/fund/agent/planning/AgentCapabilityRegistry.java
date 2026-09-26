package com.jijing.fund.agent.planning;

import java.util.Set;

/**
 * 计划允许调度的能力白名单，以及其中必须先经过人工审批的能力。
 * 未知任务类型在计划校验阶段被拒绝，不会进入执行。
 * 审批拒绝、路由失败或对等代理失败不改变白名单本身。
 */
public final class AgentCapabilityRegistry {
    public static final Set<String> WHITELIST = Set.of(
            "FUND_PROFILE_QUERY", "FUND_NAV_QUERY", "FUND_METRICS_QUERY", "FUND_COMPARE", "DOCUMENT_SEARCH",
            "REALTIME_QUOTE", "SECTOR_OUTLOOK", "CATALYST_RESEARCH", "DECLINE_ATTRIBUTION", "WATCHLIST_READ",
            "PORTFOLIO_SNAPSHOT", "PORTFOLIO_RETURN", "PORTFOLIO_RISK", "REPORT_VERIFY", "REPORT_WRITE",
            "REPORT_EXPORT");
    public static final Set<String> APPROVAL_REQUIRED = Set.of("REPORT_EXPORT");

    /**
     * 阻止实例化。白名单是固定常量，不存在可失败的初始化步骤。
     */
    private AgentCapabilityRegistry() {
    }
}
