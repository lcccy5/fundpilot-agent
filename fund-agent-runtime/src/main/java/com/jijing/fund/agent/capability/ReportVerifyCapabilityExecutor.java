package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.verification.RunVerifier;
import java.util.List;

/** 实现 ReportVerifyCapabilityExecutor 所代表的 Agent 运行时职责。 */
public final class ReportVerifyCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final RunVerifier verifier = new RunVerifier();

    
    /** 执行该 Agent 运行时组件中的 ReportVerifyCapabilityExecutor 操作。 */
    public ReportVerifyCapabilityExecutor(AgentDagRepository dag) { this.dag = dag; }
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "REPORT_VERIFY"; }

    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        String uri = "artifact://" + task.planId() + "/" + task.taskKey();
        List<AgentTaskView> tasks = dag.requireOwnedPlan(task.runId(), task.ownerUserId()).tasks().stream()
                .map(view -> view.taskId().equals(task.taskId())
                        ? new AgentTaskView(view.taskId(), view.taskKey(), view.capabilityType(), "SUCCEEDED", view.attempts(), uri)
                        : view)
                .toList();
        var report = verifier.verify(tasks);
        if (!report.passed()) throw new IllegalStateException("verification failed: " + String.join(",", report.findings()));
        return new CapabilityExecutionResult(uri, report.evidenceIds());
    }
}
