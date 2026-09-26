package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import java.util.Set;

/**
 * 限制每个对等角色可以执行的能力。
 * 能力不在计划白名单中，或角色与能力不匹配时一律拒绝。
 * 监督者不能执行任何工具。审批是否放行导出不由本类决定，只限制谁可以尝试该能力。
 */
public final class RoleToolAcl {

    /**
     * 判断角色是否可以执行指定能力。
     * 能力为 null 或不在白名单中时返回 false。数据研究员不能碰组合、自选和导出；
     * 撰写者只能写或导出；核验者只能核验；组合分析师只能处理组合和自选；
     * 风险分析师只能处理名称含 RISK 的能力以及指标查询和对比。拒绝时调用方不得强行分派。
     */
    public static boolean allowed(AgentRole role, String capabilityType) {
        if (capabilityType == null || !AgentCapabilityRegistry.WHITELIST.contains(capabilityType)) {
            return false;
        }
        return switch (role) {
            case DATA_RESEARCHER -> !capabilityType.startsWith("PORTFOLIO")
                    && !"WATCHLIST_READ".equals(capabilityType)
                    && !"REPORT_EXPORT".equals(capabilityType);
            case WRITER -> "REPORT_WRITE".equals(capabilityType) || "REPORT_EXPORT".equals(capabilityType);
            case VERIFIER -> "REPORT_VERIFY".equals(capabilityType);
            case PORTFOLIO_ANALYST -> capabilityType.startsWith("PORTFOLIO") || "WATCHLIST_READ".equals(capabilityType);
            case RISK_ANALYST -> capabilityType.contains("RISK")
                    || "FUND_METRICS_QUERY".equals(capabilityType)
                    || "FUND_COMPARE".equals(capabilityType);
            case SUPERVISOR -> false;
        };
    }

    /**
     * 返回数据研究员明确不能读取的个人数据能力。
     * 这些能力若出现在研究任务上，分派必须改派或失败，不能由数据研究员继续执行。
     */
    public static Set<String> researcherDenied() {
        return Set.of("PORTFOLIO_SNAPSHOT", "PORTFOLIO_RETURN", "PORTFOLIO_RISK", "WATCHLIST_READ");
    }
}
