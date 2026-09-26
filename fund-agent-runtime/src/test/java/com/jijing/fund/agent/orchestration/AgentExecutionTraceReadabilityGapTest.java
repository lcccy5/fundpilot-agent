package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 补齐工具调用失败的审计：失败状态不携带证据，受检异常不会被当成成功。 重复调用和超时由 {@link AgentExecutionTraceTest} 覆盖。 */
class AgentExecutionTraceReadabilityGapTest {

  /** 工具失败只写 FAILED 审计和错误码。 本轮证据列表保持为空，后续引用校验不能使用这次失败。 */
  @Test
  void toolFailureIsAuditedWithoutEvidence() {
    AgentRuntimeRepository repository = mock(AgentRuntimeRepository.class);
    AgentExecutionTrace trace =
        new AgentExecutionTrace(
            "run-fail", repository, new ObjectMapper(), 4, 2, Duration.ofSeconds(1));
    AgentExecutionTrace.ToolInvocation invocation =
        trace.begin("calculate_fund_metrics", Map.of("fundCode", "000001"));

    trace.failure(invocation, "UPSTREAM_TIMEOUT");

    assertThat(trace.evidence()).isEmpty();
    ArgumentCaptor<AgentToolCallRecord> captor = ArgumentCaptor.forClass(AgentToolCallRecord.class);
    verify(repository).recordToolCall(captor.capture());
    assertThat(captor.getValue().resultStatus()).isEqualTo("FAILED");
    assertThat(captor.getValue().errorCode()).isEqualTo("UPSTREAM_TIMEOUT");
    assertThat(captor.getValue().evidenceIds()).isEmpty();
  }

  /** 工具抛出受检异常时包成非法状态，并保留原因。 该失败不升级执行模式，只说明这一次调用没有成功。 */
  @Test
  void checkedToolFailureSurfacesAsIllegalState() {
    AgentExecutionTrace trace =
        new AgentExecutionTrace(
            "run-io",
            mock(AgentRuntimeRepository.class),
            new ObjectMapper(),
            4,
            2,
            Duration.ofSeconds(1));
    AgentExecutionTrace.ToolInvocation invocation =
        trace.begin("get_fund_profile", Map.of("fundCode", "000001"));

    assertThatThrownBy(
            () ->
                trace.call(
                    invocation,
                    () -> {
                      throw new IOException("disk");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("get_fund_profile")
        .hasCauseInstanceOf(IOException.class);
  }
}
