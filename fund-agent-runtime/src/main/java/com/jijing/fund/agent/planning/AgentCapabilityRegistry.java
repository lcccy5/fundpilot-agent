package com.jijing.fund.agent.planning;

import java.util.Set;

/** 实现 AgentCapabilityRegistry 所代表的 Agent 运行时职责。 */
public final class AgentCapabilityRegistry {
    public static final Set<String> WHITELIST=Set.of(
            "FUND_PROFILE_QUERY","FUND_NAV_QUERY","FUND_METRICS_QUERY","FUND_COMPARE","DOCUMENT_SEARCH",
            "REALTIME_QUOTE","SECTOR_OUTLOOK","CATALYST_RESEARCH","WATCHLIST_READ","PORTFOLIO_SNAPSHOT",
            "PORTFOLIO_RETURN","PORTFOLIO_RISK","REPORT_VERIFY","REPORT_WRITE","REPORT_EXPORT");
    public static final Set<String> APPROVAL_REQUIRED=Set.of("REPORT_EXPORT");
    
    /** 执行该 Agent 运行时组件中的 AgentCapabilityRegistry 操作。 */
    private AgentCapabilityRegistry(){}
}
