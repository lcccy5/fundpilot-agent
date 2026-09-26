package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * 补齐无法识别的路由：空语义建议、空目标，以及空白原文。 这些输入不得抛出未知模式，也不得升级成计划执行。顾问异常的失败开放由 {@link ExecutionModeRouterTest}
 * 覆盖。
 */
class ExecutionModeRouterReadabilityGapTest {
  private final ExecutionModeRouter router = new ExecutionModeRouter();

  /** 顾问明确返回空建议时，按确定性短任务处理。 模式必须是有界 ReAct，不能是 null 或已废弃的直接模式。 */
  @Test
  void emptySemanticAdviceFallsBackToKnownBoundedRule() {
    ExecutionModeRouter routed = new ExecutionModeRouter((message, features) -> Optional.empty());
    RouteDecision decision = routed.route("查询 000001 最新资料", true);

    assertThat(decision.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    assertThat(decision.directVariant()).isNull();
    assertThat(decision.matchedRule()).isEqualTo("SHORT_INTERACTIVE_TASK");
  }

  /** 语义建议没有可用目标时，不把它当成复杂研究。 阶段数会被夹到至少 1，未知能力名称也不会抬升路由。 */
  @Test
  void adviceWithoutGoalsStaysOnBoundedReact() {
    RouteAdvisor advisor =
        (message, features) ->
            Optional.of(
                new RouteAdvice(
                    List.of(),
                    List.of("not-a-real-capability"),
                    false,
                    false,
                    false,
                    0,
                    "unclassified"));
    RouteDecision decision = new ExecutionModeRouter(advisor).route("帮我看看这只基金", true);

    assertThat(decision.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    assertThat(decision.matchedRule()).isEqualTo("SEMANTIC_BOUNDED_TASK");
    assertThat(decision.features().semanticGoalCount()).isZero();
  }

  /** 空白原文走澄清规则，而不是抛出无法识别的路由。 没有执行权限的拒绝不在本例中，权限失败仍由路由器抛出策略异常。 */
  @Test
  void blankMessageUsesClarificationInsteadOfAnUnknownMode() {
    RouteDecision decision = router.route("  ", true);

    assertThat(decision.mode()).isEqualTo(ExecutionMode.BOUNDED_REACT);
    assertThat(decision.matchedRule()).isEqualTo("CLARIFICATION_IN_CHAT");
    assertThat(decision.features().clarificationRequired()).isTrue();
  }

  /** 预检模型没有目标或调用失败时，顾问返回空，不发明执行模式。 空白原文甚至不会发到模型。 */
  @Test
  void routeAdvisorDropsBlankAndGoallessPayloads() {
    ChatModel model =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            "{\"goals\":[],\"requiredCapabilities\":[\"unknown\"],\"estimatedStages\":3}"))));
    try (SpringAiRouteAdvisor advisor =
        new SpringAiRouteAdvisor(model, new ObjectMapper(), Duration.ofSeconds(1))) {
      RouteFeatures features = router.features("查询基金");
      assertThat(advisor.advise("   ", features)).isEmpty();
      assertThat(advisor.advise("查询基金", features)).isEmpty();
    }
  }
}
