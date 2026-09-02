package com.jijing.fund.agent.routing;

import java.util.Locale;
import java.util.regex.Pattern;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;

/** Deterministic complexity router. Models may suggest, rules decide. */
public final class ExecutionModeRouter {
    public static final String VERSION="hybrid-router-v1";
    private static final Pattern FUND=Pattern.compile("\\d{6}");
    
    /** 执行该 Agent 运行时组件中的 route 操作。 */
    public RouteDecision route(String message,boolean hasPermission){
        String text=message==null?"":message;
        // A missing permission is a rejection, not a cheaper execution mode. Routing it
        // to DIRECT made an export request look as if it had completed normally.
        if(!hasPermission)throw new AgentPolicyViolationException("agent execution permission is required");
        // Catalyst research has a resumable multi-step evidence workflow, so it must not use the transient ReAct path.
        if(message!=null&&message.contains("催化"))return decision(ExecutionMode.PLAN_AND_EXECUTE,null,features(message),"LANGGRAPH_CATALYST_RESEARCH",null);
        RouteFeatures features=features(text);
        if(features.exportOrNotificationRequested()||features.backgroundRequested())return decision(ExecutionMode.PLAN_AND_EXECUTE,null,features,"MUST_APPROVE_OR_BACKGROUND",null);
        if(features.fundCount()>=3||features.intentCount()>=2||features.reportRequested())return decision(ExecutionMode.PLAN_AND_EXECUTE,null,features,"MULTI_GOAL_OR_REPORT",null);
        if(features.estimatedToolCalls()>=3||features.freshMarketDataRequired()||features.ambiguityScore()>=0.6)return decision(ExecutionMode.BOUNDED_REACT,null,features,"NEEDS_OBSERVATION",null);
        if(features.estimatedToolCalls()>=1||features.personalDataRequired())return decision(ExecutionMode.DETERMINISTIC_TOOL,null,features,"EXPLICIT_ONE_OR_TWO_TOOLS",null);
        if(features.documentResearchRequired())return decision(ExecutionMode.DIRECT,DirectVariant.RAG_ONCE,features,"SINGLE_DOCUMENT_SEARCH",null);
        return decision(ExecutionMode.DIRECT,DirectVariant.NO_TOOL,features,"DIRECT_NO_TOOL",null);
    }
    
    /** 获取当前 Agent 操作所需的 features 结果。 */
    public RouteFeatures features(String message){
        String text=message==null?"":message.toLowerCase(Locale.ROOT);
        int funds=(int)FUND.matcher(message==null?"":message).results().count();
        boolean personal=text.contains("我的")||text.contains("组合")||text.contains("自选")||text.contains("持仓");
        boolean document=text.contains("公告")||text.contains("季报")||text.contains("招募")||text.contains("文档");
        boolean market=text.contains("为什么")||text.contains("下跌")||text.contains("行情")||text.contains("催化")||text.contains("板块");
        boolean report=text.contains("报告")||text.contains("月报")||text.contains("周报");
        boolean export=text.contains("导出")||text.contains("通知")||text.contains("发布");
        boolean background=text.contains("后台")||text.contains("稍后");
        int intents=0;if(personal)intents++;if(document)intents++;if(market)intents++;if(report)intents++;if(funds>0&&!personal&&!market&&!document&&!report)intents++;
        int estimated=funds>0||personal?1:0;if(market)estimated+=2;if(document)estimated+=1;if(report)estimated+=3;
        double ambiguity=text.contains("最复杂")||text.contains("多agent")||text.contains("plan")?0.9:market?0.7:0.1;
        return new RouteFeatures(funds,Math.max(1,intents),personal,document,market,report,export,estimated,background,ambiguity);
    }
    
    /** 执行该 Agent 运行时组件中的 decision 操作。 */
    private RouteDecision decision(ExecutionMode mode,DirectVariant variant,RouteFeatures features,String rule,String suggestion){
        return new RouteDecision(mode,variant,VERSION,features,rule,suggestion,null);
    }
}
