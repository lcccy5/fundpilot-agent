package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.verification.ReportWriter;
import com.jijing.fund.agent.verification.VerificationReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** 实现 ReportWriteCapabilityExecutor 所代表的 Agent 运行时职责。 */
public final class ReportWriteCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final ReportWriter writer = new ReportWriter();

    
    /** 执行该 Agent 运行时组件中的 ReportWriteCapabilityExecutor 操作。 */
    public ReportWriteCapabilityExecutor(AgentDagRepository dag) { this.dag = dag; }
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "REPORT_WRITE"; }

    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        var plan = dag.requireOwnedPlan(task.runId(), task.ownerUserId());
        List<String> evidence = plan.tasks().stream().filter(view -> "SUCCEEDED".equals(view.status()) && view.outputUri() != null)
                .map(AgentTaskView::outputUri).toList();
        String body = writer.write(new VerificationReport(true, List.of(), evidence), plan.tasks());
        String uri = "artifact://" + task.planId() + "/" + task.taskKey() + "#" + sha(body).substring(0, 12);
        return new CapabilityExecutionResult(uri, evidence);
    }

    
    /** 构造后续 Agent 处理所需的 sha 值。 */
    private String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("cannot hash report", error); }
    }
}
