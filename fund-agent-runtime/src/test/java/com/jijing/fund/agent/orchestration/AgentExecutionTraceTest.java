package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.time.*;
import org.junit.jupiter.api.Test;

/** 确认重复工具调用、超时和预算耗尽会中止本轮，成功观察才会写事实卡。 工具失败的审计由 {@link AgentExecutionTraceReadabilityGapTest} 覆盖。 */
class AgentExecutionTraceTest {
  /** 相同参数的第二次调用被执行限制拒绝。 该失败不按模式升级处理。 */
  @Test
  void blocksRepeatedIdenticalToolCall() {
    var trace =
        new AgentExecutionTrace(
            "run-1",
            mock(AgentRuntimeRepository.class),
            new ObjectMapper(),
            6,
            1,
            Duration.ofSeconds(1));
    trace.begin("calculate_fund_metrics", new Input("000001"));
    assertThatThrownBy(() -> trace.begin("calculate_fund_metrics", new Input("000001")))
        .isInstanceOf(AgentExecutionLimitException.class)
        .hasMessageContaining("Repeated identical");
  }

  /** 超过单次超时的工具被取消，并抛出执行限制。 超时不会把迟到结果记成成功。 */
  @Test
  void cancelsToolThatExceedsItsOwnTimeout() {
    var trace =
        new AgentExecutionTrace(
            "run-2",
            mock(AgentRuntimeRepository.class),
            new ObjectMapper(),
            6,
            1,
            Duration.ofMillis(20));
    var invocation = trace.begin("slow_tool", new Input("000001"));
    assertThatThrownBy(
            () ->
                trace.call(
                    invocation,
                    () -> {
                      Thread.sleep(500);
                      return "late";
                    }))
        .isInstanceOf(AgentExecutionLimitException.class)
        .hasMessageContaining("timed out");
  }

  /** 工具次数超过预算时抛出模式升级异常。 调用方可以据此升级为持久化计划，而不是继续循环。 */
  @Test
  void exceedingBoundedToolBudgetProducesDedicatedEscalationSignal() {
    var trace =
        new AgentExecutionTrace(
            "run-budget",
            mock(AgentRuntimeRepository.class),
            new ObjectMapper(),
            1,
            2,
            Duration.ofSeconds(1));
    trace.begin("profile", new Input("000001"));
    assertThatThrownBy(() -> trace.begin("metrics", new Input("000001")))
        .isInstanceOf(com.jijing.fund.agent.exception.AgentModeEscalationException.class)
        .hasMessageContaining("budget exhausted");
  }

  /** 成功观察会保存事实卡和原始证据。 事实卡写失败不得推翻这次成功，但本例确认成功路径确实写入。 */
  @Test
  void persistsDeterministicFactCardForSuccessfulObservation() {
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    var trace =
        new AgentExecutionTrace(
            "conversation-1",
            "run-3",
            repository,
            new ObjectMapper(),
            6,
            1,
            Duration.ofSeconds(1),
            Duration.ofHours(24),
            event -> {});
    var invocation = trace.begin("calculate_fund_metrics", new Input("000001"));
    var evidence =
        new EvidenceReference(
            "ev-1",
            "FUND_METRICS",
            "000001",
            null,
            null,
            "ACCUMULATED_NAV",
            "source",
            "v1",
            "a1",
            Instant.now());
    trace.success(invocation, evidence, new Observation("000001", "12%"));
    var captor = org.mockito.ArgumentCaptor.forClass(AgentFactCard.class);
    verify(repository).saveFactCard(captor.capture());
    assertThat(captor.getValue().evidenceIds()).containsExactly("ev-1");
    assertThat(captor.getValue().dataJson()).contains("12%");
  }

  /** 测试用的工具参数。 相同内容会得到相同签名，从而触发重复调用拒绝。 */
  record Input(String fundCode) {}

  /** 测试用的工具观察。 只有成功路径才会把它写入事实卡。 */
  record Observation(String fundCode, String returnRate) {}
}
