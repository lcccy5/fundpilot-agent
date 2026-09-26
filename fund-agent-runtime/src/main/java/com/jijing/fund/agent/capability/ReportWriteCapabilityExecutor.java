package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.verification.ReportWriter;
import com.jijing.fund.agent.verification.VerificationReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 根据计划中已经成功的任务产物撰写报告正文。
 * 运行或计划不可读时失败；没有成功产物时仍会写出一份没有证据的正文。
 */
public final class ReportWriteCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final ReportWriter writer = new ReportWriter();

    /**
     * 注入计划存储。
     * 存储为空时构造成功，执行时才会失败。
     */
    public ReportWriteCapabilityExecutor(AgentDagRepository dag) {
        this.dag = dag;
    }

    /**
     * 返回报告撰写能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "REPORT_WRITE";
    }

    /**
     * 收集已成功任务的产物地址，生成带内容摘要的报告地址。
     * 计划不可读或摘要算法不可用时失败，不返回无摘要地址。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        var plan = dag.requireOwnedPlan(task.runId(), task.ownerUserId());
        List<String> evidence = plan.tasks().stream()
                .filter(view -> "SUCCEEDED".equals(view.status()) && view.outputUri() != null)
                .map(AgentTaskView::outputUri)
                .toList();
        String body = writer.write(new VerificationReport(true, List.of(), evidence), plan.tasks());
        String uri = "artifact://" + task.planId() + "/" + task.taskKey() + "#" + sha(body).substring(0, 12);
        return new CapabilityExecutionResult(uri, evidence);
    }

    /**
     * 计算报告正文的 SHA-256 十六进制摘要。
     * 算法不可用时抛出非法状态。
     */
    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("cannot hash report", error);
        }
    }
}
