package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 实现 FundMetricsCapabilityExecutor 所代表的 Agent 运行时职责。 */
public final class FundMetricsCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundMetricsQueryUseCase metrics;
    private final ObjectMapper mapper;

    
    /** 执行该 Agent 运行时组件中的 FundMetricsCapabilityExecutor 操作。 */
    public FundMetricsCapabilityExecutor(FundMetricsQueryUseCase metrics, ObjectMapper mapper) {
        this.metrics = metrics;
        this.mapper = mapper;
    }

    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "FUND_METRICS_QUERY"; }

    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        Map<String, Object> input = CapabilityJson.input(mapper, context.task().inputJson());
        String fundCode = required(input, "fundCode");
        LocalDate start = LocalDate.parse(required(input, "startDate"));
        LocalDate end = LocalDate.parse(required(input, "endDate"));
        String navBasis = required(input, "navBasis");
        var result = metrics.calculate(fundCode, start, end, navBasis);
        String evidenceId = CapabilityJson.evidenceId("ev-metrics", context.task().inputHash());
        EvidenceReference evidence = new EvidenceReference(evidenceId, "FUND_METRICS", result.fundCode().value(),
                result.actualStartDate(), result.actualEndDate(), result.navBasis().name(), null, result.dataVersion(),
                result.algorithmVersion(), result.calculatedAt() == null ? Instant.now() : result.calculatedAt());
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, result), List.of(evidence.evidenceId()));
    }

    
    /** 执行该 Agent 运行时组件中的 required 操作。 */
    private String required(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(name + " is required");
        return String.valueOf(value);
    }
}
