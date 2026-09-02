package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.port.AgentDagRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Produces a versioned export manifest after the worker's approval gate has authorized the task. */
public final class ReportExportCapabilityExecutor implements AgentCapabilityExecutor {
    private final AgentDagRepository dag;
    private final ObjectMapper mapper;
    
    /** 执行该 Agent 运行时组件中的 ReportExportCapabilityExecutor 操作。 */
    public ReportExportCapabilityExecutor(AgentDagRepository dag, ObjectMapper mapper) { this.dag = dag; this.mapper = mapper; }
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "REPORT_EXPORT"; }

    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        String format = String.valueOf(CapabilityJson.input(mapper, task.inputJson()).getOrDefault("format", "markdown"));
        AgentTaskView report = dag.requireOwnedPlan(task.runId(), task.ownerUserId()).tasks().stream()
                .filter(view -> "REPORT_WRITE".equals(view.capabilityType()) && "SUCCEEDED".equals(view.status()) && view.outputUri() != null)
                .findFirst().orElseThrow(() -> new IllegalStateException("report artifact is required before export"));
        Map<String, Object> manifest = Map.of("format", format, "sourceReportUri", report.outputUri(), "createdAt", Instant.now().toString(), "delivery", "internal-artifact-only");
        List<String> evidence = List.of(CapabilityJson.evidenceId("ev-export", report.outputUri() + "|" + format));
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, manifest), evidence);
    }
}
