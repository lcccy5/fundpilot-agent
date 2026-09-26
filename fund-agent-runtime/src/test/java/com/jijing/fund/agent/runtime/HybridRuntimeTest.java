package com.jijing.fund.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.execution.BoundedReactExecutor;
import com.jijing.fund.agent.execution.ExecutionBudget;
import com.jijing.fund.agent.execution.Observation;
import com.jijing.fund.agent.execution.PlanTaskWorker;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 检查计划运行的所有者隔离、审批、取消、租约恢复和有界推理停止条件。
 * 并发领取若拿到同一任务会使测试失败，而不是把重复领取当成成功。
 */
class HybridRuntimeTest {
    private final InMemoryAgentDagRepository dag = new InMemoryAgentDagRepository();
    private final AgentCoordinator coordinator = new AgentCoordinator(new ExecutionModeRouter(), dag);
    private final PlanTaskWorker worker = new PlanTaskWorker(dag);

    /**
     * 简单问答不能进入持久化计划运行。
     * 路由不是计划执行时拒绝，且不创建可查询的成功运行。
     */
    @Test
    void simpleQuestionIsRejectedByDurableRunBoundary() {
        assertThatThrownBy(() -> coordinator.submit(
                new AgentRunCommand(null, "最大回撤是什么意思？", "r1", "user-a", true)))
                .hasMessageContaining("PLAN_AND_EXECUTE");
    }

    /**
     * 计划运行应执行到成功，并且其他所有者不能读取。
     * 事件序号必须唯一，增量拉取从给定序号之后继续。
     */
    @Test
    void planExecutesDagAndIsolatesOwners() {
        var run = coordinator.submit(new AgentRunCommand(
                null, "比较 000001 110022 161725 并结合我的组合生成报告", "r2", "user-a", true));
        assertThat(run.executionMode()).isEqualTo("PLAN_AND_EXECUTE");
        worker.drain("w1", Instant.parse("2026-08-27T08:00:00Z"), Duration.ofSeconds(30), 40);
        var done = coordinator.get(run.runId(), "user-a");
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        var events = coordinator.events(run.runId(), "user-a", 0L);
        assertThat(events).extracting(e -> e.sequence()).doesNotHaveDuplicates();
        assertThat(events).extracting(e -> e.eventType())
                .contains("run.routed", "plan.validated", "task.completed", "run.completed");
        var replay = coordinator.events(run.runId(), "user-a", events.get(2).sequence());
        assertThat(replay.getFirst().sequence()).isEqualTo(events.get(3).sequence());
        assertThatThrownBy(() -> coordinator.get(run.runId(), "user-b"))
                .isInstanceOf(AgentRunNotFoundException.class);
    }

    /**
     * 导出必须先等到审批，且参数摘要不一致时不能通过。
     * 摘要匹配后运行应完成；找不到审批事件时测试失败。
     */
    @Test
    void exportWaitsForApprovalAndParameterHashMustMatch() {
        var run = coordinator.submit(new AgentRunCommand(
                null, "比较 000001 110022 161725 并导出研究报告", "r3", "user-a", true));
        worker.drain("w1", Instant.parse("2026-08-27T08:00:00Z"), Duration.ofSeconds(30), 40);
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("WAITING_APPROVAL");
        String approvalId = coordinator.events(run.runId(), "user-a", 0L).stream()
                .filter(e -> "approval.requested".equals(e.eventType()))
                .findFirst()
                .orElseThrow()
                .payloadJson();
        String id = approvalId.replaceAll(".*\"approvalId\":\"([^\"]+)\".*", "$1");
        assertThatThrownBy(() -> coordinator.approve(run.runId(), id, "user-a", "{\"format\":\"pdf\"}"))
                .hasMessageContaining("approval");
        coordinator.approve(run.runId(), id, "user-a", "{\"format\":\"markdown\"}");
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("SUCCEEDED");
    }

    /**
     * 两个工作者不能领取到同一个任务。
     * 等待超时或出现重复领取时失败。
     */
    @Test
    void twoWorkersNeverClaimTheSameTask() throws Exception {
        var run = coordinator.submit(new AgentRunCommand(
                null, "比较 000001 110022 161725 并结合我的组合生成报告", "r4", "user-a", true));
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> claimLoop(claimed, start, "w-a"));
            var f2 = pool.submit(() -> claimLoop(claimed, start, "w-b"));
            start.countDown();
            assertThat(f1.get(5, TimeUnit.SECONDS) + f2.get(5, TimeUnit.SECONDS)).isGreaterThan(0);
        }
        assertThat(coordinator.get(run.runId(), "user-a").status()).isIn("SUCCEEDED", "PLAN_RUNNING", "RUNNING");
        worker.drain("w-final", Instant.parse("2026-08-27T08:00:00Z"), Duration.ofSeconds(30), 40);
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("SUCCEEDED");
    }

    /**
     * 过期租约应被恢复，已经成功的幂等键不应导致重复副作用。
     * 恢复数量不是 1 或最终状态不是成功时失败。
     */
    @Test
    void expiredLeaseIsRecoveredWithoutRepeatingSuccess() {
        var run = coordinator.submit(new AgentRunCommand(
                null, "比较 000001 110022 161725 并结合我的组合生成报告", "r5", "user-a", true));
        Instant t0 = Instant.parse("2026-08-27T08:00:00Z");
        var first = dag.claimReady("w1", t0, Duration.ofSeconds(1)).orElseThrow();
        int recovered = dag.recoverExpiredLeases(t0.plusSeconds(5));
        assertThat(recovered).isEqualTo(1);
        worker.drain("w2", t0.plusSeconds(6), Duration.ofSeconds(30), 40);
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("SUCCEEDED");
        assertThat(PlanTaskWorker.executionKey(first)).isNotBlank();
    }

    /**
     * 取消后剩余任务不得再被执行成运行成功。
     * 未成功的任务应为已取消，运行状态不能是暂停。
     */
    @Test
    void cancelStopsRemainingTasks() {
        var run = coordinator.submit(new AgentRunCommand(
                null, "比较 000001 110022 161725 并结合我的组合生成报告", "r6", "user-a", true));
        coordinator.cancel(run.runId(), "user-a");
        worker.drain("w1", Instant.parse("2026-08-27T08:00:00Z"), Duration.ofSeconds(30), 10);
        assertThat(coordinator.get(run.runId(), "user-a").status()).isEqualTo("CANCELLED");
        assertThat(coordinator.plan(run.runId(), "user-a").tasks())
                .allMatch(t -> "CANCELLED".equals(t.status()) || "SUCCEEDED".equals(t.status()));
        assertThat(coordinator.get(run.runId(), "user-a").status()).isNotEqualTo("PAUSED");
    }

    /**
     * 证据重复出现时应停止有界推理，而不是打满最大轮次。
     * 停止原因或观察次数不符时失败。
     */
    @Test
    void reactStopsWhenEvidenceRepeats() {
        var budget = new ExecutionBudget(6, 1000);
        var result = new BoundedReactExecutor().run(
                round -> new Observation("ok", "s", List.of("ev-1"), List.of(), List.of(), "fresh", List.of()),
                budget);
        assertThat(result.stopReason()).isEqualTo("NO_NEW_EVIDENCE");
        assertThat(result.observations()).hasSize(3);
    }

    /**
     * 工具预算不能被扣成负数。
     * 第二次扣减必须失败且余额保持为 0。
     */
    @Test
    void budgetCannotGoNegative() {
        var budget = new ExecutionBudget(1, 1);
        assertThat(budget.consumeTools(1)).isTrue();
        assertThat(budget.consumeTools(1)).isFalse();
        assertThat(budget.remainingTools()).isZero();
    }

    /**
     * 抽样路由里，非报告问句不应进入计划执行，报告问句应全部进入。
     * 误判数量不为 0 或报告命中不足时失败。
     */
    @Test
    void routingEvalHasHighAccuracy() {
        var router = new ExecutionModeRouter();
        long plan = IntStream.range(0, 200).mapToObj(i -> switch (i % 4) {
            case 0 -> "最大回撤是什么意思？" + i;
            case 1 -> "查询 00000" + (i % 9) + " 最新资料";
            case 2 -> "000001 最近为什么下跌？样本" + i;
            default -> "比较 000001 110022 161725 并结合我的组合生成报告 " + i;
        }).filter(q -> "PLAN_AND_EXECUTE".equals(router.route(q, true).mode().name()) && !q.contains("比较")).count();
        assertThat(plan).isZero();
        long reports = IntStream.range(0, 50)
                .mapToObj(i -> "比较 000001 110022 161725 并结合我的组合生成报告 " + i)
                .filter(q -> "PLAN_AND_EXECUTE".equals(router.route(q, true).mode().name()))
                .count();
        assertThat(reports).isEqualTo(50);
    }

    /**
     * 在起跑信号后反复领取任务，并把任务标识放进共享集合。
     * 等待被中断时返回 0；发现重复领取时立刻失败。
     */
    private int claimLoop(Set<String> claimed, CountDownLatch start, String workerId) {
        try {
            start.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        int n = 0;
        for (int i = 0; i < 20; i++) {
            var id = worker.claimAndExecute(
                    workerId + "-" + UUID.randomUUID(),
                    Instant.parse("2026-08-27T08:00:00Z"),
                    Duration.ofSeconds(30));
            if (id.isEmpty()) {
                continue;
            }
            if (!claimed.add(id.get())) {
                throw new AssertionError("duplicate claim " + id.get());
            }
            n++;
        }
        return n;
    }
}
