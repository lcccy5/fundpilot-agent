package com.jijing.fund.agent.verification;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.ArrayList;
import java.util.List;

/**
 * 核对一次研究运行是否具备写报告的条件。报告类任务本身不参与完成性检查，因为它们要等本次核对通过后才能开始。
 * 存在未成功的研究任务或缺少核对任务时，报告不通过；成功任务没有输出地址时不会单独记成缺少引用。
 */
public final class RunVerifier {
    /**
     * 检查研究任务是否都已成功，并收集它们的输出地址作为证据。
     * 已取消和等待审批的任务被跳过。没有状态为成功以外的研究任务、且存在核对任务时 passed 为 true。
     * 证据列表可以仍为空，这不构成单独的失败。
     */
    public VerificationReport verify(List<AgentTaskView> tasks) {
        List<String> findings = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (AgentTaskView task : tasks) {
            // 核对的是已经完成的研究输入。报告阶段是下游步骤，必须等本次核对通过后才能开始，所以不计入未完成。
            if ("REPORT_VERIFY".equals(task.capabilityType()) || "REPORT_WRITE".equals(task.capabilityType())
                    || "REPORT_EXPORT".equals(task.capabilityType())) {
                continue;
            }
            if ("CANCELLED".equals(task.status()) || "WAITING_APPROVAL".equals(task.status())) {
                continue;
            }
            if (!"SUCCEEDED".equals(task.status())) {
                findings.add("incomplete:" + task.taskKey());
            }
            if (task.outputUri() != null) {
                evidence.add(task.outputUri());
            }
        }
        if (tasks.stream().noneMatch(t -> "REPORT_VERIFY".equals(t.capabilityType()) || "verify".equals(t.taskKey()))) {
            findings.add("missing-verifier");
        }
        return new VerificationReport(findings.isEmpty(), List.copyOf(findings), List.copyOf(evidence));
    }
}
