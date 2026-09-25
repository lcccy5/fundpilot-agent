package com.jijing.fund.agent.report;

import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.multiagent.MultiAgentEnablementPolicy;
import com.jijing.fund.agent.multiagent.MultiAgentSupervisor;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Instant;
import java.util.HexFormat;

/** Monthly reports reuse V4 Plan-and-Execute. Ordinary chat never calls this. */
public final class MonthlyReportLauncher {
    public static final String GOAL="比较 000001 110022 161725 并结合我的组合生成报告";
    private final AgentRunUseCase runs;
    private final ReportJobStore jobs;
    private final MultiAgentSupervisor supervisor;
    private final MultiAgentEnablementPolicy policy;
    private final Clock clock;
    
    /** 执行该 Agent 运行时组件中的 MonthlyReportLauncher 操作。 */
    public MonthlyReportLauncher(AgentRunUseCase runs){
        this(runs,new InMemoryReportJobStore(),Clock.systemUTC());
    }
    
    /** 执行该 Agent 运行时组件中的 MonthlyReportLauncher 操作。 */
    public MonthlyReportLauncher(AgentRunUseCase runs,ReportJobStore jobs,Clock clock){
        this.runs=runs;
        this.jobs=jobs==null?new InMemoryReportJobStore():jobs;
        this.clock=clock==null?Clock.systemUTC():clock;
        this.supervisor=new MultiAgentSupervisor(new ExecutionModeRouter(),new PlanValidator(),new RuleBasedPlanner());
        this.policy=new MultiAgentEnablementPolicy();
    }
    
    /** 创建并初始化当前 Agent 操作所需的 launch 结果。 */
    public AgentRunView launch(String ownerUserId,double singleQuality,double multiQuality,double extraCost){
        boolean multi=policy.enable(singleQuality,multiQuality,extraCost);
        var assignment=supervisor.decide(GOAL,true,multi,ownerUserId);
        if(assignment.plan()!=null)new PlanValidator().validate(assignment.plan(),ownerUserId);
        var run=runs.submit(new AgentRunCommand(null,GOAL,"monthly-"+ownerUserId,ownerUserId,true));
        LocalDate end=LocalDate.now(clock);
        jobs.create(ownerUserId,run.runId(),end.minusMonths(1),end,clock.instant());
        return run;
    }

    /** Starts a report only for a chosen month and a scope that already has funds. */
    public AgentRunView launch(String ownerUserId, MonthlyReportScope scope) {
        if (scope == null || scope.month() == null || scope.month().isBlank())
            throw new AgentInvalidArgumentException("请选择月份，以及一个有数据的组合或自选");
        if (scope.fundCodes().isEmpty())
            throw new AgentInvalidArgumentException(missing(scope));
        YearMonth month;
        try { month = YearMonth.parse(scope.month()); }
        catch (Exception error) { throw new AgentInvalidArgumentException("月份格式应为 YYYY-MM"); }
        YearMonth current = YearMonth.now(clock);
        if (month.isAfter(current))
            throw new AgentInvalidArgumentException(scope.month() + " 还没到，不能生成这份月报");
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        LocalDate today = LocalDate.now(clock);
        if (end.isAfter(today)) end = today;
        String kind = "WATCHLIST".equals(scope.kind()) ? "自选" : "组合";
        String label = scope.label() == null || scope.label().isBlank() ? kind : scope.label();
        String goal = "比较 " + String.join(" ", scope.fundCodes()) + " 期间" + start + "至" + end
                + " 并结合我的" + kind + "「" + label + "」生成报告";
        var run = runs.submit(new AgentRunCommand(null, goal, "monthly-" + ownerUserId, ownerUserId, true));
        jobs.create(ownerUserId, run.runId(), start, end, clock.instant());
        return run;
    }

    private static String missing(MonthlyReportScope scope) {
        String name = scope.label() == null || scope.label().isBlank() ? "" : "「" + scope.label() + "」";
        if ("WATCHLIST".equals(scope.kind())) return "自选" + name + "里还没有基金，无法生成月报";
        return "组合" + name + "还没有持仓，无法生成月报";
    }

    /**
     * A report is materialized only after the V4 writer task has completed.  This keeps
     * report_job truthful when a run is cancelled, waiting for approval, or fails later.
     */
    public void reconcile(ReportJobStore.ReportJobView job,String ownerUserId){
        if(job==null||!ownerUserId.equals(job.ownerUserId())||"SUCCEEDED".equals(job.status()))return;
        var run=runs.get(job.runId(),ownerUserId);
        if(!"SUCCEEDED".equals(run.status()))return;
        var writer=runs.plan(job.runId(),ownerUserId).tasks().stream()
                .filter(task->"REPORT_WRITE".equals(task.capabilityType())&&"SUCCEEDED".equals(task.status())&&task.outputUri()!=null)
                .findFirst();
        if(writer.isEmpty())return;
        String uri=writer.get().outputUri();
        jobs.saveVersionedArtifact(job.jobId(),ownerUserId,uri,sha(uri),
                "{\"runId\":\""+job.runId()+"\",\"writerTask\":\""+writer.get().taskKey()+"\"}",
                clock.instant(),"fund-agent-v1","configured",clock.instant());
    }
    
    /** 构造后续 Agent 处理所需的 sha 值。 */
    private static String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
