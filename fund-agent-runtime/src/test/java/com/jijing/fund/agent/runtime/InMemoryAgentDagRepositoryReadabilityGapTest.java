package com.jijing.fund.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.exception.TaskLeaseLostException;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 补齐内存计划存储上缺失运行、尚未保存计划和失败落库的路径。
 * 过期租约上的失败必须拒绝，不能把运行改成失败。
 */
class InMemoryAgentDagRepositoryReadabilityGapTest {
    private final InMemoryAgentDagRepository dag = new InMemoryAgentDagRepository();

    /**
     * 没有计划的运行不能被当成已有计划读取，事件追加也要求运行先存在。
     * 空所有者与错误所有者一样被拒绝。
     */
    @Test
    void missingRunAndMissingPlanAreRejected() {
        Instant now = Instant.parse("2026-08-27T08:00:00Z");
        assertThat(dag.findRun("missing")).isEmpty();
        assertThatThrownBy(() -> dag.requireOwnedRun("missing", "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> dag.appendEvent("missing", "run.note", "{}", now))
                .isInstanceOf(AgentRunNotFoundException.class)
                .hasMessageContaining("run not found");

        String runId = dag.startRun("conversation", "user-a", "req", "PLAN_AND_EXECUTE", "rule", now);
        assertThatThrownBy(() -> dag.requireOwnedRun(runId, null))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThatThrownBy(() -> dag.requireOwnedPlan(runId, "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class)
                .hasMessageContaining("plan not found");
    }

    /**
     * 空失败原因仍会把运行标成失败；租约到期后再失败则拒绝且保持原状态。
     * 第二种情况不能覆盖成失败。
     */
    @Test
    void failedExecutionRequiresALiveLease() {
        var coordinator = new AgentCoordinator(new ExecutionModeRouter(), dag);
        var run = coordinator.submit(new AgentRunCommand(
                null,
                "比较 000001 110022 161725 并结合我的组合生成报告",
                "gap-lease",
                "user-a",
                true));
        Instant t0 = Instant.parse("2026-08-27T08:00:00Z");
        var claim = dag.claimReady("worker", t0, Duration.ofMillis(3)).orElseThrow();
        dag.failTask(claim, null, t0.plusMillis(1));
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("FAILED");
        assertThat(coordinator.events(run.runId(), "user-a", 0L))
                .anyMatch(event -> "run.failed".equals(event.eventType()) && event.payloadJson().contains("任务执行失败"));

        var second = coordinator.submit(new AgentRunCommand(
                null,
                "比较 000001 110022 161725 并结合我的组合生成报告",
                "gap-stale",
                "user-a",
                true));
        Instant t1 = Instant.parse("2026-08-27T09:00:00Z");
        var stale = dag.claimReady("worker", t1, Duration.ofMillis(3)).orElseThrow();
        assertThatThrownBy(() -> dag.failTask(stale, "late", t1.plusMillis(3)))
                .isInstanceOf(TaskLeaseLostException.class);
        assertThat(coordinator.get(second.runId(), "user-a").status()).isNotEqualTo("FAILED");
    }
}
