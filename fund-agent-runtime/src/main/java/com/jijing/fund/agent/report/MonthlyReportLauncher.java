package com.jijing.fund.agent.report;

import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.multiagent.MultiAgentEnablementPolicy;
import com.jijing.fund.agent.multiagent.MultiAgentSupervisor;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * 用既有的计划执行入口启动月报。普通对话不会调用这里。
 * 所有者为空时提交会被拒绝；运行尚未成功或正文任务未完成时，对账不会写入版本。
 */
public final class MonthlyReportLauncher {
    public static final String GOAL = "比较 000001 110022 161725 并结合我的组合生成报告";
    private final AgentRunUseCase runs;
    private final ReportJobStore jobs;
    private final MultiAgentSupervisor supervisor;
    private final MultiAgentEnablementPolicy policy;
    private final Clock clock;

    /**
     * 使用内存任务存储和系统 UTC 时钟创建启动器。
     * 运行入口为空时构造成功，启动时才会失败。
     */
    public MonthlyReportLauncher(AgentRunUseCase runs) {
        this(runs, new InMemoryReportJobStore(), Clock.systemUTC());
    }

    /**
     * 使用调用方提供的任务存储和时钟。
     * 存储或时钟为空时改用内存存储和系统 UTC 时钟，不因此失败。
     */
    public MonthlyReportLauncher(AgentRunUseCase runs, ReportJobStore jobs, Clock clock) {
        this.runs = runs;
        this.jobs = jobs == null ? new InMemoryReportJobStore() : jobs;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.supervisor = new MultiAgentSupervisor(new ExecutionModeRouter(), new PlanValidator(), new RuleBasedPlanner());
        this.policy = new MultiAgentEnablementPolicy();
    }

    /**
     * 按质量策略决定是否启用多智能体，然后提交固定目标的计划运行并登记月报任务。
     * 所有者为空或计划校验失败时拒绝；返回的是运行快照，不是月报任务本身。
     */
    public AgentRunView launch(String ownerUserId, double singleQuality, double multiQuality, double extraCost) {
        boolean multi = policy.enable(singleQuality, multiQuality, extraCost);
        var assignment = supervisor.decide(GOAL, true, multi, ownerUserId);
        if (assignment.plan() != null) {
            new PlanValidator().validate(assignment.plan(), ownerUserId);
        }
        var run = runs.submit(new AgentRunCommand(null, GOAL, "monthly-" + ownerUserId, ownerUserId, true));
        LocalDate end = LocalDate.now(clock);
        jobs.create(ownerUserId, run.runId(), end.minusMonths(1), end, clock.instant());
        return run;
    }

    /**
     * 仅在计划运行和报告正文任务都成功后，才把月报任务写成一个版本。
     * 任务为空、所有者不匹配、运行未成功或正文缺失时直接返回；运行不存在时由运行入口拒绝。
     */
    public void reconcile(ReportJobStore.ReportJobView job, String ownerUserId) {
        if (job == null || !ownerUserId.equals(job.ownerUserId()) || "SUCCEEDED".equals(job.status())) {
            return;
        }
        var run = runs.get(job.runId(), ownerUserId);
        if (!"SUCCEEDED".equals(run.status())) {
            return;
        }
        var writer = runs.plan(job.runId(), ownerUserId).tasks().stream()
                .filter(task -> "REPORT_WRITE".equals(task.capabilityType())
                        && "SUCCEEDED".equals(task.status())
                        && task.outputUri() != null)
                .findFirst();
        if (writer.isEmpty()) {
            return;
        }
        String uri = writer.get().outputUri();
        jobs.saveVersionedArtifact(
                job.jobId(),
                ownerUserId,
                uri,
                sha(uri),
                "{\"runId\":\"" + job.runId() + "\",\"writerTask\":\"" + writer.get().taskKey() + "\"}",
                clock.instant(),
                "fund-agent-v1",
                "configured",
                clock.instant());
    }

    /**
     * 计算产物地址的 SHA-256 十六进制摘要。
     * 算法不可用时抛出非法状态，月报版本不会被写入。
     */
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
