package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.*;

/** 确认语义预检能解析有界 JSON，坏响应则失败开放。 顾问不选择执行模式，解析失败不得升级计划。 */
class SpringAiRouteAdvisorTest {
  /** 模型返回完整 JSON 时保留跨来源和阶段数。 这些特征仍须由确定性路由器决定是否进入计划。 */
  @Test
  void parsesBoundedStructuredAdvice() {
    ChatModel model =
        prompt ->
            new ChatResponse(
                List.of(
                    new Generation(
                        new AssistantMessage(
                            """
{"goals":["核验异常","分析原因"],"requiredCapabilities":["market_quote","document_search"],
 "hasDependencies":true,"crossSourceVerificationRequired":true,
 "iterativeResearchRequired":true,"estimatedStages":3,
 "rationale":"requires cross-source verification"}
"""))),
                ChatResponseMetadata.builder().model("router-test").build());
    try (var advisor = new SpringAiRouteAdvisor(model, new ObjectMapper(), Duration.ofSeconds(1))) {
      var advice = advisor.advise("这只基金最近不太对劲", new ExecutionModeRouter().features("这只基金最近不太对劲"));
      assertThat(advice).isPresent();
      assertThat(advice.orElseThrow().crossSourceVerificationRequired()).isTrue();
      assertThat(advice.orElseThrow().estimatedStages()).isEqualTo(3);
    }
  }

  /** 无法解析的模型正文变成空建议。 调用方应回退确定性规则，而不是当成未知模式。 */
  @Test
  void malformedProviderResponseFailsOpen() {
    ChatModel model =
        prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage("not-json"))));
    try (var advisor = new SpringAiRouteAdvisor(model, new ObjectMapper(), Duration.ofSeconds(1))) {
      assertThat(advisor.advise("查询基金", new ExecutionModeRouter().features("查询基金"))).isEmpty();
    }
  }
}
