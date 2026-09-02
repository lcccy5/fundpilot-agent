package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import java.util.Set;

/** 实现 RoleToolAcl 所代表的 Agent 运行时职责。 */
public final class RoleToolAcl {
    
    /** 判断 allowed 对应的条件是否成立。 */
    public static boolean allowed(AgentRole role,String capabilityType){
        if(capabilityType==null||!AgentCapabilityRegistry.WHITELIST.contains(capabilityType))return false;
        return switch(role){
            case DATA_RESEARCHER -> !capabilityType.startsWith("PORTFOLIO")&&!"WATCHLIST_READ".equals(capabilityType)&&!"REPORT_EXPORT".equals(capabilityType);
            case WRITER -> "REPORT_WRITE".equals(capabilityType)||"REPORT_EXPORT".equals(capabilityType);
            case VERIFIER -> "REPORT_VERIFY".equals(capabilityType);
            case PORTFOLIO_ANALYST -> capabilityType.startsWith("PORTFOLIO")||"WATCHLIST_READ".equals(capabilityType);
            case RISK_ANALYST -> capabilityType.contains("RISK")||"FUND_METRICS_QUERY".equals(capabilityType)||"FUND_COMPARE".equals(capabilityType);
            case SUPERVISOR -> false;
        };
    }
    
    /** 执行该 Agent 运行时组件中的 researcherDenied 操作。 */
    public static Set<String> researcherDenied(){return Set.of("PORTFOLIO_SNAPSHOT","PORTFOLIO_RETURN","PORTFOLIO_RISK","WATCHLIST_READ");}
}
