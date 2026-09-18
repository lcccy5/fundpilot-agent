package com.jijing.fund.agent.routing;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;

/** Deterministic complexity router. Models may suggest, rules decide. */
public final class ExecutionModeRouter {
    public static final String VERSION="hybrid-router-v4";
    private static final Pattern FUND=Pattern.compile("\\d{6}");
    private final RouteAdvisor advisor;

    public ExecutionModeRouter(){this((message,features)->Optional.empty());}
    public ExecutionModeRouter(RouteAdvisor advisor){
        this.advisor=Objects.requireNonNull(advisor,"advisor is required");
    }
    
    /** 执行该 Agent 运行时组件中的 route 操作。 */
    public RouteDecision route(String message,boolean hasPermission){
        String text=message==null?"":message;
        // A missing permission is a rejection, not a cheaper execution mode. Routing it
        // to DIRECT made an export request look as if it had completed normally.
        if(!hasPermission)throw new AgentPolicyViolationException("agent execution permission is required");
        RouteFeatures features=features(text);
        if(features.backgroundRequested()||features.approvalRequired()||features.sideEffectRequested())
            return decision(ExecutionMode.PLAN_AND_EXECUTE,features,"DURABLE_OR_APPROVAL_REQUIRED");
        if(features.reportRequested()||features.estimatedStages()>=2)
            return decision(ExecutionMode.PLAN_AND_EXECUTE,features,"MULTI_STAGE_OR_REPORT");
        if(features.adaptiveResearchRequired())
            return decision(ExecutionMode.PLAN_AND_EXECUTE,features,"ADAPTIVE_RESEARCH_REQUIRED");
        Optional<RouteAdvice> advice;
        try{advice=advisor.advise(text,features);}catch(RuntimeException ignored){advice=Optional.empty();}
        if(advice.isPresent()){
            RouteAdvice value=advice.get();
            RouteFeatures enriched=features.withSemanticAdvice(value);
            if(semanticRequiresPlan(value))return semanticDecision(enriched,value,"SEMANTIC_COMPLEXITY");
            return decision(ExecutionMode.BOUNDED_REACT,enriched,features.clarificationRequired()?"CLARIFICATION_IN_CHAT":"SEMANTIC_BOUNDED_TASK");
        }
        return decision(ExecutionMode.BOUNDED_REACT,features,features.clarificationRequired()?"CLARIFICATION_IN_CHAT":"SHORT_INTERACTIVE_TASK");
    }
    
    /** 获取当前 Agent 操作所需的 features 结果。 */
    public RouteFeatures features(String message){
        String text=message==null?"":message.toLowerCase(Locale.ROOT);
        int funds=(int)FUND.matcher(message==null?"":message).results().count();
        boolean personal=text.contains("我的")||text.contains("组合")||text.contains("自选")||text.contains("持仓");
        boolean document=text.contains("公告")||text.contains("季报")||text.contains("招募")||text.contains("文档");
        boolean market=text.contains("为什么")||text.contains("下跌")||text.contains("行情")||text.contains("催化")||text.contains("板块");
        boolean report=text.contains("生成报告")||text.contains("研究报告")||text.contains("月报")||text.contains("周报");
        boolean export=text.contains("导出")||text.contains("通知")||text.contains("发布");
        boolean background=text.contains("后台")||text.contains("稍后");
        boolean adaptive=containsAny(text,"深度研究","深入研究","全面研究","催化","归因","多份资料","证据是否充分","风格是否发生变化");
        boolean clarification=text.length()<4||containsAny(text,"随便看看","帮我分析一下","哪个好")&&funds==0;
        int intents=0;if(personal)intents++;if(document)intents++;if(market)intents++;if(report)intents++;if(funds>0)intents++;
        int estimated=funds>0||personal?1:0;if(market)estimated+=2;if(document)estimated+=1;if(report)estimated+=3;
        boolean dependent=containsAny(text,"并结合","然后","再根据","并生成","综合")&&intents>=2;
        int stages=report?Math.max(2,intents):dependent?2:1;
        return new RouteFeatures(funds,Math.max(1,intents),estimated,stages,personal,document,market,report,
                export,export,background,adaptive,clarification,0,0,0,false,false,false);
    }
    
    /** 执行该 Agent 运行时组件中的 decision 操作。 */
    private RouteDecision decision(ExecutionMode mode,RouteFeatures features,String rule){
        return new RouteDecision(mode,null,VERSION,features,rule,null,null);
    }
    private RouteDecision semanticDecision(RouteFeatures features,RouteAdvice advice,String rule){
        return new RouteDecision(ExecutionMode.PLAN_AND_EXECUTE,null,VERSION,features,rule,
                "SEMANTIC_FEATURES",advice.rationale());
    }
    private boolean semanticRequiresPlan(RouteAdvice advice){
        boolean dependentGoals=advice.goals().size()>=2&&advice.hasDependencies()&&advice.estimatedStages()>=2;
        boolean crossSource=advice.crossSourceVerificationRequired()&&advice.requiredCapabilities().size()>=2;
        boolean iterative=advice.iterativeResearchRequired()&&advice.estimatedStages()>=2;
        return dependentGoals||crossSource||iterative||advice.estimatedStages()>=3;
    }
    private boolean containsAny(String text,String... values){return java.util.Arrays.stream(values).anyMatch(text::contains);}
}
