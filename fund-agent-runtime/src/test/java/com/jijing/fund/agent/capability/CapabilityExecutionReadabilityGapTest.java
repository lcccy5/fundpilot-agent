package com.jijing.fund.agent.capability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.runtime.InMemoryAgentDagRepository;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 补齐能力注册、输入解析和核对失败这些还没有测试锁定的失败路径。
 * 断言对应当前抛出的异常，不改执行器去迎合测试。
 */
class CapabilityExecutionReadabilityGapTest {

    /**
     * 空执行器、空类型和未注册类型都应在执行前被拒绝。
     * 空集合本身可以构成注册表，但随后任何具体类型都找不到执行器。
     */
    @Test
    void nullExecutorAndMissingCapabilityAreRejected() {
        assertThatThrownBy(() -> new CapabilityExecutorRegistry(java.util.Arrays.asList((AgentCapabilityExecutor) null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> new CapabilityExecutorRegistry(List.of(blankType())))
                .isInstanceOf(IllegalArgumentException.class);
        var registry = new CapabilityExecutorRegistry(List.of());
        assertThatThrownBy(() -> registry.require("FUND_METRICS_QUERY"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no executor");
        assertThatThrownBy(() -> new CapabilityExecutionContext(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityExecutionResult("  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outputUri");
    }

    /**
     * 非法 JSON 和缺少基金代码的指标任务都应在查询指标前失败。
     * 不调用被替身替换的指标用例。
     */
    @Test
    void invalidMetricInputFailsBeforeCalculation() {
        var executor = new FundMetricsCapabilityExecutor(mock(FundMetricsQueryUseCase.class), new ObjectMapper());
        var badJson = new ClaimedTask(
                "t", "r", "p", 1, "metrics", "FUND_METRICS_QUERY", "not-json", "h", 1, "user-a");
        assertThatThrownBy(() -> executor.execute(new CapabilityExecutionContext(badJson)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid JSON");
        var missingFund = new ClaimedTask(
                "t", "r", "p", 1, "metrics", "FUND_METRICS_QUERY", "{}", "h", 1, "user-a");
        assertThatThrownBy(() -> executor.execute(new CapabilityExecutionContext(missingFund)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fundCode");
    }

    /**
     * 研究任务尚未成功时，核对能力必须失败而不是交出产物。
     * 计划来自真实提交，避免手写一套与生产不一致的任务图。
     */
    @Test
    void verificationFailsWhenResearchTasksAreIncomplete() {
        var dag = new InMemoryAgentDagRepository();
        var coordinator = new AgentCoordinator(new ExecutionModeRouter(), dag);
        var run = coordinator.submit(new AgentRunCommand(
                null,
                "比较 000001 110022 161725 并结合我的组合生成报告",
                "gap-verify",
                "user-a",
                true));
        var claim = new ClaimedTask(
                "not-in-plan",
                run.runId(),
                run.planId(),
                1,
                "verify",
                "REPORT_VERIFY",
                "{}",
                "h",
                1,
                "user-a");
        assertThatThrownBy(() -> new ReportVerifyCapabilityExecutor(dag).execute(new CapabilityExecutionContext(claim)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verification failed");
    }

    /**
     * 提供一个类型为空白的执行器，用来触发注册拒绝。
     * 它的执行方法不应被调用。
     */
    private static AgentCapabilityExecutor blankType() {
        return new AgentCapabilityExecutor() {
            /**
             * 返回空白类型。
             * 不会失败，拒绝发生在注册表构造时。
             */
            @Override
            public String capabilityType() {
                return " ";
            }

            /**
             * 不应被执行。
             * 一旦调用就使测试失败。
             */
            @Override
            public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
                throw new AssertionError("blank executor must not run");
            }
        };
    }
}
