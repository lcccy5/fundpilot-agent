package com.jijing.fund.agent.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.capability.AgentCapabilityExecutor;
import com.jijing.fund.agent.capability.CapabilityExecutionContext;
import com.jijing.fund.agent.capability.CapabilityExecutionResult;
import com.jijing.fund.agent.capability.CapabilityExecutorRegistry;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.runtime.InMemoryAgentDagRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 补齐协调器上尚未覆盖的空命令、缺失运行和执行失败路径。
 * 这些断言锁定当前拒绝行为，不要求生产代码为了测试而改变。
 */
class AgentCoordinatorReadabilityGapTest {
    private final InMemoryAgentDagRepository dag = new InMemoryAgentDagRepository();
    private final AgentCoordinator coordinator = new AgentCoordinator(new ExecutionModeRouter(), dag);

    /**
     * 空命令、空消息、空白消息和空所有者都应在写入运行前被拒绝。
     * 存储中不应因此出现运行。
     */
    @Test
    void nullOrBlankCommandIsRejectedBeforeARunIsCreated() {
        assertThatThrownBy(() -> coordinator.submit(null))
                .isInstanceOf(AgentInvalidArgumentException.class)
                .hasMessageContaining("message and owner");
        assertThatThrownBy(() -> coordinator.submit(new AgentRunCommand("c", null, "r", "user-a", true)))
                .isInstanceOf(AgentInvalidArgumentException.class);
        assertThatThrownBy(() -> coordinator.submit(new AgentRunCommand("c", "  ", "r", "user-a", true)))
                .isInstanceOf(AgentInvalidArgumentException.class);
        assertThatThrownBy(() -> coordinator.submit(new AgentRunCommand("c", "比较基金", "r", null, true)))
                .isInstanceOf(AgentInvalidArgumentException.class);
        assertThat(dag.findRun("missing")).isEmpty();
    }

    /**
     * 查询、取消、审批和拒绝在运行不存在时都应拒绝。
     * 不区分标识不存在和所有者不匹配。
     */
    @Test
    void missingRunIsRejectedOnEveryOwnerQuery() {
        assertThatThrownBy(() -> coordinator.get("missing-run", "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> coordinator.plan("missing-run", "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> coordinator.events("missing-run", "user-a", null))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> coordinator.cancel("missing-run", "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> coordinator.approve("missing-run", "approval", "user-a", "{}"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> coordinator.reject("missing-run", "approval", "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
    }

    /**
     * 能力执行抛错时，运行应被标成失败，其余未成功任务不再继续。
     * 租约仍然有效，因此这次失败允许落库。
     */
    @Test
    void failedCapabilityMarksTheRunFailed() {
        var run = coordinator.submit(new AgentRunCommand(
                null,
                "比较 000001 110022 161725 并结合我的组合生成报告",
                "gap-fail",
                "user-a",
                true));
        var worker = new PlanTaskWorker(dag, new CapabilityExecutorRegistry(List.of(failingMetrics())));
        assertThat(worker.claimAndExecute("gap-worker", Instant.parse("2026-08-27T08:00:00Z"), Duration.ofSeconds(30)))
                .isPresent();
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("FAILED");
        assertThat(coordinator.plan(run.runId(), "user-a").tasks())
                .anyMatch(task -> "FAILED".equals(task.status()));
    }

    /**
     * 工具预算为 0 时一步都不执行；模型要求停止时保留已产生的观察。
     * 步函数返回空观察时按当前实现失败，而不是记成正常停止。
     */
    @Test
    void boundedReactStopsOnBudgetAndRejectsANullObservation() {
        var exhausted = new BoundedReactExecutor().run(
                round -> {
                    throw new AssertionError("budget should stop before the step");
                },
                new ExecutionBudget(0, 10));
        assertThat(exhausted.stopReason()).isEqualTo("BUDGET_TOOLS");
        assertThat(exhausted.observations()).isEmpty();

        var stopped = new BoundedReactExecutor().run(
                round -> new Observation("STOP", "done", List.of("ev-stop"), List.of(), List.of(), "fresh", List.of()),
                new ExecutionBudget(3, 10));
        assertThat(stopped.stopReason()).isEqualTo("MODEL_STOP");
        assertThat(stopped.observations()).hasSize(1);

        assertThatThrownBy(() -> new BoundedReactExecutor().run(round -> null, new ExecutionBudget(2, 2)))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 构造一个指标能力，执行时固定失败。
     * 不访问真实指标服务。
     */
    private static AgentCapabilityExecutor failingMetrics() {
        return new AgentCapabilityExecutor() {
            /**
             * 返回指标查询类型，使注册表能匹配就绪任务。
             * 不会失败。
             */
            @Override
            public String capabilityType() {
                return "FUND_METRICS_QUERY";
            }

            /**
             * 拒绝执行并给出固定原因。
             * 始终抛出非法状态，不返回产物。
             */
            @Override
            public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
                throw new IllegalStateException("metrics down");
            }
        };
    }
}
