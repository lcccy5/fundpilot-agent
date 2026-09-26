package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.tool.FundToolRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** 用假模型确认有界对话、流式失败、事实复用、计划路由和预算升级。 空计划、未知路由和审批拒绝不在本类里改生产行为。 */
class SpringAiFundAgentServiceTest {
  /** 假模型的正常回答会完成运行并记成功指标。 没有工具证据时回答仍可结束，限制由策略另行生成。 */
  @Test
  void completesConversationWithFakeModelAndAudit() {
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists("00000000-0000-0000-0000-000000000001")).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("run-1");
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            6,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt prompt) {
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage("我可以解释基金历史数据，但不承诺未来收益。"))),
                ChatResponseMetadata.builder().model("fake-model").build());
          }
        };
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    String promptText = "你是基金助手，不得承诺收益。";
    var prompt =
        new FundAgentPrompt("fund-agent-v1", promptText, AgentExecutionTrace.sha256(promptText));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            meters,
            prompt,
            new AgentModelDescriptor("test-provider", "configured-test-model"))) {
      FundAgentResponse result =
          service.chat(
              new FundAgentRequest("00000000-0000-0000-0000-000000000001", "你能做什么？", "req-1"));
      assertThat(result.answer()).contains("不承诺");
      assertThat(result.modelName()).isEqualTo("fake-model");
      assertThat(result.evidence()).isEmpty();
      verify(repository).completeRun(eq("run-1"), eq(1), eq(0), any(), anyLong(), any());
      assertThat(meters.counter("fund.agent.runs", "status", "success").count()).isEqualTo(1);
    }
  }

  /** 流式增量合并成完整句子后再完成审计。 模型失败时不会发出完成事件。 */
  @Test
  void emitsIncrementalDeltasAndCompletesStreamAudit() {
    String conversation = "00000000-0000-0000-0000-000000000002";
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(conversation)).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("run-stream");
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            6,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt p) {
            return Flux.just(response("这是"), response("增量回答。"));
          }

          private ChatResponse response(String text) {
            return new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("fake-stream-model").build());
          }
        };
    String promptText = "你是基金助手。";
    var prompt =
        new FundAgentPrompt("fund-agent-v1", promptText, AgentExecutionTrace.sha256(promptText));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake-stream-model"))) {
      var events =
          service.stream(new FundAgentRequest(conversation, "你好", "req-stream"))
              .collectList()
              .block();
      assertThat(events)
          .extracting(FundAgentEvent::type)
          .containsExactly("run.started", "answer.delta", "evidence.verifying", "answer.completed");
      assertThat(events.get(1).data()).isEqualTo("这是增量回答。");
      verify(repository).completeRun(eq("run-stream"), eq(1), eq(0), any(), anyLong(), any());
    }
  }

  /** 模型把工具调用写成正文时，流记为失败且不发出增量。 该失败是模型协议错误，不会升级成计划。 */
  @Test
  void rejectsTextualPseudoToolCallsBeforeStreamingThemToTheUser() {
    String conversation = "00000000-0000-0000-0000-000000000005";
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(conversation)).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("run-protocol");
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            6,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt p) {
            throw new UnsupportedOperationException();
          }

          @Override
          public Flux<ChatResponse> stream(Prompt p) {
            return Flux.just(
                new ChatResponse(
                    List.of(
                        new Generation(
                            new AssistantMessage(
                                "{\"name\":\"sector_outlook\",\"arguments\":{\"sector\":\"机器人\"}}"))),
                    ChatResponseMetadata.builder().model("fake-stream-model").build()));
          }
        };
    String promptText = "你是基金助手。";
    var prompt =
        new FundAgentPrompt("fund-agent-v1", promptText, AgentExecutionTrace.sha256(promptText));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake-stream-model"))) {
      var events =
          service.stream(new FundAgentRequest(conversation, "机器人板块最近咋样", "req-protocol"))
              .collectList()
              .block();
      assertThat(events)
          .extracting(FundAgentEvent::type)
          .containsExactly("run.started", "run.failed");
      assertThat(events).noneMatch(event -> "answer.delta".equals(event.type()));
      verify(repository)
          .failRun(
              eq("run-protocol"),
              eq("FAILED"),
              eq("MODEL_UNAVAILABLE"),
              anyString(),
              eq(0),
              anyLong(),
              any());
    }
  }

  /** 未过期的事实卡把原始证据带回回答。 过期卡不能被当成这次运行的新证据。 */
  @Test
  void reusesUnexpiredFactCardWithItsOriginalEvidence() {
    String conversation = "00000000-0000-0000-0000-000000000003";
    Instant now = Instant.parse("2026-08-23T00:00:00Z");
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(conversation)).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("run-fact");
    var evidence =
        new EvidenceReference(
            "ev-old",
            "FUND_METRICS",
            "000001",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            "ACCUMULATED_NAV",
            "source",
            "v1",
            "a1",
            now);
    when(repository.findActiveFactCards(eq(conversation), any(), anyInt()))
        .thenReturn(
            List.of(
                new AgentFactCard(
                    "card-1",
                    conversation,
                    "old-run",
                    "calculate_fund_metrics",
                    "000001",
                    List.of(evidence),
                    "{\"return\":\"12%\"}",
                    now.minusSeconds(60),
                    now.plusSeconds(3600))));
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            6,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        p ->
            new ChatResponse(
                List.of(new Generation(new AssistantMessage("该区间收益为12% ev-old。"))),
                ChatResponseMetadata.builder().model("fake-model").build());
    var prompt =
        new FundAgentPrompt("fund-agent-v1", "你是基金助手。", AgentExecutionTrace.sha256("你是基金助手。"));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(now, ZoneOffset.UTC),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake"))) {
      FundAgentResponse response =
          service.chat(new FundAgentRequest(conversation, "刚才的收益是多少？", "req-fact"));
      assertThat(response.answer()).contains("ev-old");
      assertThat(response.evidence()).contains(evidence);
      verify(repository).recordFactCardUsage("run-fact", List.of("card-1"), now);
    }
  }

  /** 复杂报告请求提交持久化计划，并且不调用主模型。 路由或提交失败时不会假装已经开始有界回答。 */
  @Test
  void chatRoutesComplexRequestToDurablePlan() {
    String conversation = "00000000-0000-0000-0000-000000000004";
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(eq(conversation), any())).thenReturn(true);
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            6,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        new ChatModel() {
          @Override
          public ChatResponse call(Prompt p) {
            throw new AssertionError("model must not be called for plan-and-execute chat");
          }
        };
    AgentRunUseCase runs = mock(AgentRunUseCase.class);
    when(runs.submit(any()))
        .thenReturn(
            new AgentRunView(
                "plan-run",
                conversation,
                "user-a",
                "PLAN_RUNNING",
                "PLAN_AND_EXECUTE",
                "MULTI_GOAL_OR_REPORT",
                "plan-1",
                1));
    var actor =
        new com.jijing.fund.domain.identity.AuthenticatedUser(
            new com.jijing.fund.domain.identity.UserId("00000000-0000-0000-0000-000000000001"),
            Set.of(com.jijing.fund.domain.identity.UserRole.USER),
            "s");
    var prompt =
        new FundAgentPrompt("fund-agent-v1", "你是基金助手。", AgentExecutionTrace.sha256("你是基金助手。"));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake"),
            new com.jijing.fund.agent.routing.ExecutionModeRouter(),
            runs)) {
      FundAgentResponse response =
          service.chat(
              new FundAgentRequest(
                  conversation, "比较 000001 110022 161725 并结合我的组合生成报告", "req-plan", actor));
      assertThat(response.runId()).isEqualTo("plan-run");
      assertThat(response.answer()).contains("普通问答使用有限 ReAct");
      verify(runs).submit(any());
      verify(repository, never())
          .startRun(
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              anyString(),
              any());
    }
  }

  /** 已认证用户的预算耗尽会升级为一次持久化任务。 原运行记为拒绝，重复升级不会发生。 */
  @Test
  void executionLimitPromotesAuthenticatedBoundedRunOnce() {
    String conversation = "00000000-0000-0000-0000-000000000006";
    var userId = new com.jijing.fund.domain.identity.UserId("00000000-0000-0000-0000-000000000001");
    var actor =
        new com.jijing.fund.domain.identity.AuthenticatedUser(
            userId, Set.of(com.jijing.fund.domain.identity.UserRole.USER), "s");
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(conversation, userId)).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("bounded-run");
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            4,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        prompt -> {
          throw new com.jijing.fund.agent.exception.AgentModeEscalationException(
              "Bounded ReAct tool budget exhausted");
        };
    AgentRunUseCase runs = mock(AgentRunUseCase.class);
    when(runs.submit(any()))
        .thenReturn(
            new AgentRunView(
                "plan-run",
                conversation,
                userId.value(),
                "PLAN_RUNNING",
                "PLAN_AND_EXECUTE",
                "RUNTIME_BUDGET_ESCALATION",
                "plan-1",
                1));
    var prompt =
        new FundAgentPrompt("fund-agent-v1", "你是基金助手。", AgentExecutionTrace.sha256("你是基金助手。"));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake"),
            new com.jijing.fund.agent.routing.ExecutionModeRouter(),
            runs)) {
      FundAgentResponse response =
          service.chat(new FundAgentRequest(conversation, "帮我看看 000001", "req-limit", actor));
      assertThat(response.runId()).isEqualTo("plan-run");
      assertThat(response.answer()).contains("自动升级");
      verify(repository)
          .failRun(
              eq("bounded-run"),
              eq("REJECTED"),
              eq("AGENT_MODE_ESCALATION"),
              anyString(),
              eq(0),
              anyLong(),
              any());
      verify(repository)
          .recordRouteDecision(
              eq("bounded-run"),
              eq(userId.value()),
              argThat(d -> d.mode() == com.jijing.fund.agent.routing.ExecutionMode.BOUNDED_REACT),
              any());
      var command = org.mockito.ArgumentCaptor.forClass(AgentRunCommand.class);
      verify(runs, times(1)).submit(command.capture());
      assertThat(command.getValue().requestId()).isEqualTo("req-limit-escalated");
      assertThat(command.getValue().routeDecision().matchedRule())
          .isEqualTo("RUNTIME_BUDGET_ESCALATION");
      verify(repository).linkEscalatedRun("plan-run", "bounded-run");
    }
  }

  /** 重复调用限制只拒绝运行，不提交升级计划。 这类失败不能被当成可恢复的预算耗尽。 */
  @Test
  void repeatedCallLimitDoesNotPromoteInfrastructureOrLoopFailure() {
    String conversation = "00000000-0000-0000-0000-000000000007";
    var userId = new com.jijing.fund.domain.identity.UserId("00000000-0000-0000-0000-000000000001");
    var actor =
        new com.jijing.fund.domain.identity.AuthenticatedUser(
            userId, Set.of(com.jijing.fund.domain.identity.UserRole.USER), "s");
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    when(repository.conversationExists(conversation, userId)).thenReturn(true);
    when(repository.startRun(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any()))
        .thenReturn("bounded-loop-run");
    FundToolRouter router = mock(FundToolRouter.class);
    when(router.toolsFor(anyString())).thenReturn(new Object[] {});
    ChatMemory memory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(new InMemoryChatMemoryRepository())
            .maxMessages(20)
            .build();
    FundAgentProperties properties =
        FundAgentProperties.of(
            true,
            "fund-agent-v1",
            "fund-tools-v1",
            4,
            1,
            5,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            2000,
            20);
    ChatModel model =
        prompt -> {
          throw new com.jijing.fund.agent.exception.AgentExecutionLimitException(
              "Repeated identical tool call blocked");
        };
    AgentRunUseCase runs = mock(AgentRunUseCase.class);
    var prompt =
        new FundAgentPrompt("fund-agent-v1", "你是基金助手。", AgentExecutionTrace.sha256("你是基金助手。"));
    try (var service =
        new SpringAiFundAgentService(
            model,
            memory,
            repository,
            properties,
            router,
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new FundAgentSafetyPolicy(),
            new FundAgentCitationPolicy(),
            new SimpleMeterRegistry(),
            prompt,
            new AgentModelDescriptor("test", "fake"),
            new com.jijing.fund.agent.routing.ExecutionModeRouter(),
            runs)) {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  service.chat(
                      new FundAgentRequest(conversation, "帮我看看 000001", "req-loop", actor)))
          .isInstanceOf(com.jijing.fund.agent.exception.AgentExecutionLimitException.class);
      verify(repository)
          .failRun(
              eq("bounded-loop-run"),
              eq("REJECTED"),
              eq("AGENT_EXECUTION_LIMIT"),
              anyString(),
              eq(0),
              anyLong(),
              any());
      verifyNoInteractions(runs);
    }
  }
}
