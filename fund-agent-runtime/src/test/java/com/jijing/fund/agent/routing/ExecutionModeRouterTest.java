package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutionModeRouterTest {
    private final ExecutionModeRouter router=new ExecutionModeRouter();
    @Test void simpleConceptUsesBoundedReactWithoutForcingTools(){
        var d=router.route("最大回撤是什么意思？",true);
        assertThat(d.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
        assertThat(d.directVariant()).isNull();
    }
    @Test void singleFundQueryUsesBoundedReact(){
        assertThat(router.route("查询 000001 最新资料",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void explorationUsesBoundedReact(){
        assertThat(router.route("000001 最近为什么下跌？",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void multiFundReportUsesPlan(){
        assertThat(router.route("比较 000001 110022 161725 并结合我的组合生成报告",true).mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    }
    @Test void multiFundComparisonCanStayInBoundedReact(){
        assertThat(router.route("比较 000001 110022 161725 的收益",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void adaptiveResearchUsesPlan(){
        assertThat(router.route("深度研究 000001 的下跌原因",true).mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    }
    @Test void plannerAssignsAdaptiveDeclineResearchToLangGraphCapability(){
        var plan=new com.jijing.fund.agent.planning.RuleBasedPlanner().draft("深度研究 000001 的下跌原因");
        assertThat(plan.tasks()).extracting(com.jijing.fund.agent.planning.PlanTaskDraft::taskType)
                .containsExactly("DECLINE_ATTRIBUTION");
    }
    @Test void routeDecisionIsAuditableAndVersioned(){
        var decision=router.route("查询 000001 最新资料",true);
        assertThat(decision.routerVersion()).isEqualTo("hybrid-router-v4");
        assertThat(decision.matchedRule()).isEqualTo("SHORT_INTERACTIVE_TASK");
    }
    @Test void semanticComplexityPromotesImplicitRequest(){
        RouteAdvisor advisor=(message,features)->Optional.of(new RouteAdvice(
                List.of("核验异常表现","检查外部事件"),List.of("market_quote","document_search"),true,true,true,3,"cross-source investigation"));
        var decision=new ExecutionModeRouter(advisor).route("这只基金最近感觉不太对，帮我弄清楚",true);
        assertThat(decision.mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
        assertThat(decision.matchedRule()).isEqualTo("SEMANTIC_COMPLEXITY");
        assertThat(decision.modelSuggestion()).isEqualTo("SEMANTIC_FEATURES");
        assertThat(decision.features().semanticGoalCount()).isEqualTo(2);
    }
    @Test void simpleSemanticFeaturesStayInBoundedReact(){
        RouteAdvisor advisor=(message,features)->Optional.of(new RouteAdvice(
                List.of("查询基金概况"),List.of("fund_profile"),false,false,false,1,"single lookup"));
        var decision=new ExecutionModeRouter(advisor).route("帮我看看这只基金",true);
        assertThat(decision.mode())
                .isEqualTo(ExecutionMode.BOUNDED_REACT);
        assertThat(decision.matchedRule()).isEqualTo("SEMANTIC_BOUNDED_TASK");
    }
    @Test void mandatoryRulesRunBeforeSemanticAdvisor(){
        RouteAdvisor advisor=(message,features)->{throw new AssertionError("mandatory route must not call model advisor");};
        var decision=new ExecutionModeRouter(advisor).route("导出我的组合报告",true);
        assertThat(decision.mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
        assertThat(decision.matchedRule()).isEqualTo("DURABLE_OR_APPROVAL_REQUIRED");
    }
    @Test void unavailableSemanticAdvisorFailsOpenToBoundedReact(){
        RouteAdvisor advisor=(message,features)->{throw new IllegalStateException("provider unavailable");};
        assertThat(new ExecutionModeRouter(advisor).route("查询 000001 最新资料",true).mode())
                .isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void ambiguousQuestionStaysInChatForClarification(){
        assertThat(router.route("哪个好",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void userCannotForceExpensiveMode(){
        assertThat(router.route("请使用最复杂模式回答：什么是净值？",true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    }
    @Test void deniedPermissionDoesNotPlan(){
        assertThatThrownBy(()->router.route("导出我的组合报告",false))
                .isInstanceOf(AgentPolicyViolationException.class);
    }
}
