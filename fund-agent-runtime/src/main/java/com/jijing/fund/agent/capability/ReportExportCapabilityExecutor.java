package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 在工作者已经授权副作用之后，生成带版本信息的导出清单。
 * 报告正文任务尚未成功时拒绝导出，不写出清单。
 */
public final class ReportExportCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final ObjectMapper mapper;

    /**
     * 注入计划存储和 JSON 映射器。
     * 依赖为空时构造成功，执行时才会失败。
     */
    public ReportExportCapabilityExecutor(AgentDagRepository dag, ObjectMapper mapper) {
        this.dag = dag;
        this.mapper = mapper;
    }

    /**
     * 返回报告导出能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "REPORT_EXPORT";
    }

    /**
     * 找到已成功的报告正文任务，生成仅内部投递的导出清单。
     * 运行或计划不可读、没有成功的正文产物、或清单无法序列化时失败。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        String format = String.valueOf(CapabilityJson.input(mapper, task.inputJson()).getOrDefault("format", "markdown"));
        AgentTaskView report = dag.requireOwnedPlan(task.runId(), task.ownerUserId()).tasks().stream()
                .filter(view -> "REPORT_WRITE".equals(view.capabilityType())
                        && "SUCCEEDED".equals(view.status())
                        && view.outputUri() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("report artifact is required before export"));
        Map<String, Object> manifest = Map.of(
                "format", format,
                "sourceReportUri", report.outputUri(),
                "createdAt", Instant.now().toString(),
                "delivery", "internal-artifact-only");
        List<String> evidence = List.of(CapabilityJson.evidenceId("ev-export", report.outputUri() + "|" + format));
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, manifest), evidence);
    }
}
