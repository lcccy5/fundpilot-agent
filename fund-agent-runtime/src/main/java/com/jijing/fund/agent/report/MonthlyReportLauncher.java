package com.jijing.fund.agent.report;

import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.multiagent.MultiAgentEnablementPolicy;
import com.jijing.fund.agent.multiagent.MultiAgentSupervisor;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Clock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
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
        var job=jobs.create(ownerUserId,run.runId(),end.minusMonths(1),end,clock.instant());
        return run;
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
