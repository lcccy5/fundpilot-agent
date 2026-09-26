package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 确认确定性规则优先于语义建议，权限缺失时拒绝路由而不是降级。 语义顾问不可用时失败开放到有界 ReAct，不发明未知模式。 */
class ExecutionModeRouterTest {
  private final ExecutionModeRouter router = new ExecutionModeRouter();

  /** 概念解释留在有界 ReAct，并且不强制直接模式。 用户要求更复杂的模式也不能改写这条规则。 */
  @Test
  void simpleConceptUsesBoundedReactWithoutForcingTools() {
    var d = router.route("最大回撤是什么意思？", true);
    assertThat(d.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    assertThat(d.directVariant()).isNull();
  }

  /** 单只基金查询不进入计划执行。 路由失败时不会改成已废弃的直接模式。 */
  @Test
  void singleFundQueryUsesBoundedReact() {
    assertThat(router.route("查询 000001 最新资料", true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 单只基金的下跌追问仍是有界交互。 没有报告或多阶段信号时不升级计划。 */
  @Test
  void explorationUsesBoundedReact() {
    assertThat(router.route("000001 最近为什么下跌？", true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 多基金结合组合并生成报告时进入计划执行。 该请求后续还要经过计划校验和审批，路由本身不执行导出。 */
  @Test
  void multiFundReportUsesPlan() {
    assertThat(router.route("比较 000001 110022 161725 并结合我的组合生成报告", true).mode())
        .isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
  }

  /** 只比较收益、不要求报告时可以留在有界 ReAct。 基金数量本身不是升级计划的充分条件。 */
  @Test
  void multiFundComparisonCanStayInBoundedReact() {
    assertThat(router.route("比较 000001 110022 161725 的收益", true).mode())
        .isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 深度研究类请求进入计划执行。 自适应研究失败不会回退成未知路由。 */
  @Test
  void adaptiveResearchUsesPlan() {
    assertThat(router.route("深度研究 000001 的下跌原因", true).mode())
        .isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
  }

  /** 下跌归因被收成一个 LangGraph 能力，而不是空计划。 外层任务失败时不会拆出未校验的补救步骤。 */
  @Test
  void plannerAssignsAdaptiveDeclineResearchToLangGraphCapability() {
    var plan = new com.jijing.fund.agent.planning.RuleBasedPlanner().draft("深度研究 000001 的下跌原因");
    assertThat(plan.tasks())
        .extracting(com.jijing.fund.agent.planning.PlanTaskDraft::taskType)
        .containsExactly("DECLINE_ATTRIBUTION");
  }

  /** 路由结果带有版本和命中规则，便于审计。 缺少这些字段就不能区分失败开放和强制规则。 */
  @Test
  void routeDecisionIsAuditableAndVersioned() {
    var decision = router.route("查询 000001 最新资料", true);
    assertThat(decision.routerVersion()).isEqualTo("hybrid-router-v4");
    assertThat(decision.matchedRule()).isEqualTo("SHORT_INTERACTIVE_TASK");
  }

  /** 语义特征表明跨来源调查时，把含糊请求升到计划执行。 建议不可用时不得走这条规则。 */
  @Test
  void semanticComplexityPromotesImplicitRequest() {
    RouteAdvisor advisor =
        (message, features) ->
            Optional.of(
                new RouteAdvice(
                    List.of("核验异常表现", "检查外部事件"),
                    List.of("market_quote", "document_search"),
                    true,
                    true,
                    true,
                    3,
                    "cross-source investigation"));
    var decision = new ExecutionModeRouter(advisor).route("这只基金最近感觉不太对，帮我弄清楚", true);
    assertThat(decision.mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    assertThat(decision.matchedRule()).isEqualTo("SEMANTIC_COMPLEXITY");
    assertThat(decision.modelSuggestion()).isEqualTo("SEMANTIC_FEATURES");
    assertThat(decision.features().semanticGoalCount()).isEqualTo(2);
  }

  /** 单目标语义建议留在有界 ReAct。 空泛建议不能把请求升级成计划。 */
  @Test
  void simpleSemanticFeaturesStayInBoundedReact() {
    RouteAdvisor advisor =
        (message, features) ->
            Optional.of(
                new RouteAdvice(
                    List.of("查询基金概况"),
                    List.of("fund_profile"),
                    false,
                    false,
                    false,
                    1,
                    "single lookup"));
    var decision = new ExecutionModeRouter(advisor).route("帮我看看这只基金", true);
    assertThat(decision.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    assertThat(decision.matchedRule()).isEqualTo("SEMANTIC_BOUNDED_TASK");
  }

  /** 导出和审批类请求在调用顾问之前就进入计划执行。 顾问即使失败也不会被问到。 */
  @Test
  void mandatoryRulesRunBeforeSemanticAdvisor() {
    RouteAdvisor advisor =
        (message, features) -> {
          throw new AssertionError("mandatory route must not call model advisor");
        };
    var decision = new ExecutionModeRouter(advisor).route("导出我的组合报告", true);
    assertThat(decision.mode()).isEqualTo(ExecutionMode.PLAN_AND_EXECUTE);
    assertThat(decision.matchedRule()).isEqualTo("DURABLE_OR_APPROVAL_REQUIRED");
  }

  /** 顾问抛出运行时异常时回退到有界 ReAct。 失败不升级为计划，也不抛出未知路由。 */
  @Test
  void unavailableSemanticAdvisorFailsOpenToBoundedReact() {
    RouteAdvisor advisor =
        (message, features) -> {
          throw new IllegalStateException("provider unavailable");
        };
    assertThat(new ExecutionModeRouter(advisor).route("查询 000001 最新资料", true).mode())
        .isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 过短或含糊的问题留在对话里澄清。 不会因为无法分类而选择计划执行。 */
  @Test
  void ambiguousQuestionStaysInChatForClarification() {
    assertThat(router.route("哪个好", true).mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 用户在原文里指定复杂模式时仍然按规则路由。 该指令被当作数据，不能覆盖安全边界。 */
  @Test
  void userCannotForceExpensiveMode() {
    assertThat(router.route("请使用最复杂模式回答：什么是净值？", true).mode())
        .isEqualTo(ExecutionMode.BOUNDED_REACT);
  }

  /** 没有执行权限时抛出策略异常，不产生计划。 导出请求因此不会看起来已经完成。 */
  @Test
  void deniedPermissionDoesNotPlan() {
    assertThatThrownBy(() -> router.route("导出我的组合报告", false))
        .isInstanceOf(AgentPolicyViolationException.class);
  }
}
