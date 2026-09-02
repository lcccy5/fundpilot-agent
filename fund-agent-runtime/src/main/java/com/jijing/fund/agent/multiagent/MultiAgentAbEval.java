package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.util.ArrayList;
import java.util.List;

/** Deterministic A/B over a fixture dataset. Live-model scoring is a separate opt-in gate. */
public final class MultiAgentAbEval {
    
    /** 在 Agent 运行时边界间传递 Case 数据的不可变值对象。 */
    public record Case(String id,String kind,String message,double singleQuality,double multiQuality,double extraCost){}
    
    /** 在 Agent 运行时边界间传递 CaseResult 数据的不可变值对象。 */
    public record CaseResult(String id,String kind,String routedMode,boolean multiAgentStarted,double singleQuality,double multiQuality){}
    
    /** 在 Agent 运行时边界间传递 Report 数据的不可变值对象。 */
    public record Report(double avgSingleQuality,double avgMultiQuality,double extraCost,boolean enableMultiAgent,int ordinaryQaMultiStarts,List<CaseResult> cases){}

    private final ExecutionModeRouter router=new ExecutionModeRouter();
    private final MultiAgentSupervisor supervisor=new MultiAgentSupervisor(router,new PlanValidator(),new RuleBasedPlanner());
    private final MultiAgentEnablementPolicy policy=new MultiAgentEnablementPolicy();

    
    /** 执行该 Agent 运行时组件中的 evaluate 操作。 */
    public Report evaluate(List<Case> cases){
        List<CaseResult> results=new ArrayList<>();
        int qaMulti=0;
        double singleSum=0,multiSum=0,costSum=0;
        int n=0;
        for(Case c:cases){
            var route=router.route(c.message(),true);
            boolean requested=c.kind().equals("report");
            var assignment=supervisor.decide(c.message(),true,requested);
            if("qa".equals(c.kind())&&assignment.multiAgent())qaMulti++;
            results.add(new CaseResult(c.id(),c.kind(),route.mode().name(),assignment.multiAgent(),c.singleQuality(),c.multiQuality()));
            if("report".equals(c.kind())){
                singleSum+=c.singleQuality();
                multiSum+=c.multiQuality();
                costSum+=c.extraCost();
                n++;
            }
        }
        double avgS=n==0?0:singleSum/n;
        double avgM=n==0?0:multiSum/n;
        double cost=n==0?1:costSum/n;
        return new Report(avgS,avgM,cost,policy.enable(avgS,avgM,cost),qaMulti,List.copyOf(results));
    }

    
    /** 执行该 Agent 运行时组件中的 defaultDataset 操作。 */
    public static List<Case> defaultDataset(){
        return List.of(
                new Case("qa-drawdown","qa","最大回撤是什么意思？",1.00,1.00,1.0),
                new Case("lookup-000001","lookup","查询 000001 最新资料",0.92,0.90,1.1),
                new Case("explore-drop","explore","000001 最近为什么下跌？",0.80,0.82,1.3),
                new Case("report-3funds","report","比较 000001 110022 161725 并结合我的组合生成报告",0.80,0.81,1.2)
        );
    }
}
