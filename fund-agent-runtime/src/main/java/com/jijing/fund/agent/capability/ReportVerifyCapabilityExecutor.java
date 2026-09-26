package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.verification.RunVerifier;
import java.util.List;

/**
 * 在撰写报告前核对本运行的研究任务是否都已成功。
 * 核对未通过时抛出非法状态，工作者会把任务记为失败。
 */
public final class ReportVerifyCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final RunVerifier verifier = new RunVerifier();

    /**
     * 注入计划存储。
     * 存储为空时构造成功，执行时才会失败。
     */
    public ReportVerifyCapabilityExecutor(AgentDagRepository dag) {
        this.dag = dag;
    }

    /**
     * 返回报告核对能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "REPORT_VERIFY";
    }

    /**
     * 把当前任务视作已成功后核对其余研究任务。
     * 计划不可读或核对发现未完成任务时失败，不返回产物。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        String uri = "artifact://" + task.planId() + "/" + task.taskKey();
        List<AgentTaskView> tasks = dag.requireOwnedPlan(task.runId(), task.ownerUserId()).tasks().stream()
                .map(view -> view.taskId().equals(task.taskId())
                        ? new AgentTaskView(view.taskId(), view.taskKey(), view.capabilityType(), "SUCCEEDED",
                                view.attempts(), uri)
                        : view)
                .toList();
        var report = verifier.verify(tasks);
        if (!report.passed()) {
            throw new IllegalStateException("verification failed: " + String.join(",", report.findings()));
        }
        return new CapabilityExecutionResult(uri, report.evidenceIds());
    }
}
