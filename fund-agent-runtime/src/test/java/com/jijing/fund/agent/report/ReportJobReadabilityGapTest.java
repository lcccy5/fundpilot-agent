package com.jijing.fund.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.runtime.InMemoryAgentDagRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

/**
 * 补齐月报任务在空所有者、缺失任务和缺失运行上的失败路径。
 * 对账遇到不存在的运行时应拒绝，而不是把任务标成成功。
 */
class ReportJobReadabilityGapTest {
    private final InMemoryReportJobStore jobs = new InMemoryReportJobStore();

    /**
     * 找不到的任务不能读出版本，也不能写入产物。
     * 查询接口返回空，写入接口失败。
     */
    @Test
    void missingReportJobCannotBeVersioned() {
        assertThat(jobs.findOwned("missing", "user-a")).isEmpty();
        assertThatThrownBy(() -> jobs.latestVersion("missing", "user-a"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> jobs.saveVersionedArtifact(
                "missing",
                "user-a",
                "artifact://report",
                "hash",
                "{}",
                Instant.parse("2026-08-27T08:00:00Z"),
                "prompt",
                "model",
                Instant.parse("2026-08-27T08:00:00Z")))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * 空所有者不能启动月报；对账一个不存在的运行时必须拒绝。
     * 空任务对账则直接返回，不访问运行存储。
     */
    @Test
    void nullOwnerAndMissingRunFailTheLauncher() {
        var dag = new InMemoryAgentDagRepository();
        var runs = new AgentCoordinator(new ExecutionModeRouter(), dag);
        var clock = java.time.Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC);
        var launcher = new MonthlyReportLauncher(runs, jobs, clock);
        assertThatThrownBy(() -> launcher.launch(null, 0.8, 0.81, 1.2))
                .isInstanceOf(AgentInvalidArgumentException.class);
        launcher.reconcile(null, "user-a");
        var job = jobs.create("user-a", "missing-run", LocalDate.parse("2026-07-27"), LocalDate.parse("2026-08-27"),
                Instant.parse("2026-08-27T08:00:00Z"));
        assertThatThrownBy(() -> launcher.reconcile(job, "user-a"))
                .isInstanceOf(AgentRunNotFoundException.class);
        assertThat(jobs.latestVersion(job.jobId(), "user-a")).isZero();
    }
}
