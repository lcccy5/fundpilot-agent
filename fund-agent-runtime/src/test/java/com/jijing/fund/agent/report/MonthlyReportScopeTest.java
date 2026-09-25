package com.jijing.fund.agent.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.runtime.InMemoryAgentDagRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlyReportScopeTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T08:00:00Z"), ZoneOffset.UTC);

    @Test void emptyScopeDoesNotStartAJob() {
        var jobs = new InMemoryReportJobStore();
        var launcher = launcher(jobs);
        assertThatThrownBy(() -> launcher.launch("user-a", new MonthlyReportScope("2026-07", "PORTFOLIO", "默认组合", List.of())))
                .isInstanceOf(AgentInvalidArgumentException.class)
                .hasMessageContaining("还没有持仓");
        assertThat(jobs.listOwned("user-a")).isEmpty();
    }

    @Test void chosenMonthAndFundsAreStoredOnTheJob() {
        var dag = new InMemoryAgentDagRepository();
        var jobs = new InMemoryReportJobStore();
        var launcher = new MonthlyReportLauncher(new AgentCoordinator(new ExecutionModeRouter(), dag), jobs, clock);
        var run = launcher.launch("user-a", new MonthlyReportScope("2026-07", "WATCHLIST", "核心", List.of("000001")));
        var job = jobs.listOwned("user-a").getFirst();
        assertThat(job.runId()).isEqualTo(run.runId());
        assertThat(job.periodStart()).isEqualTo(LocalDate.parse("2026-07-01"));
        assertThat(job.periodEnd()).isEqualTo(LocalDate.parse("2026-07-31"));
        var plan = new RuleBasedPlanner(clock).draft("比较 000001 期间2026-07-01至2026-07-31 并结合我的自选「核心」生成报告");
        assertThat(plan.inputSnapshot()).containsEntry("startDate", "2026-07-01");
        assertThat(plan.inputSnapshot()).containsEntry("endDate", "2026-07-31");
        assertThat(plan.tasks()).anyMatch(task -> "000001".equals(String.valueOf(task.input().get("fundCode"))));
    }

    private MonthlyReportLauncher launcher(InMemoryReportJobStore jobs) {
        return new MonthlyReportLauncher(new AgentCoordinator(new ExecutionModeRouter(), new InMemoryAgentDagRepository()), jobs, clock);
    }
}
