package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.FundComparisonUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 实现 FundComparisonCapabilityExecutor 所代表的 Agent 运行时职责。 */
public final class FundComparisonCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundComparisonUseCase comparison;
    private final ObjectMapper mapper;

    
    /** 执行该 Agent 运行时组件中的 FundComparisonCapabilityExecutor 操作。 */
    public FundComparisonCapabilityExecutor(FundComparisonUseCase comparison, ObjectMapper mapper) {
        this.comparison = comparison;
        this.mapper = mapper;
    }

    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "FUND_COMPARE"; }

    @Override @SuppressWarnings("unchecked") 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        Map<String, Object> input = CapabilityJson.input(mapper, context.task().inputJson());
        Object values = input.get("fundCodes");
        if (!(values instanceof List<?> raw) || raw.size() < 2) throw new IllegalArgumentException("fundCodes must contain at least two values");
        List<String> codes = raw.stream().map(String::valueOf).toList();
        var result = comparison.compare(codes, LocalDate.parse(required(input, "startDate")), LocalDate.parse(required(input, "endDate")), required(input, "navBasis"));
        List<String> evidence = result.funds().stream()
                .map(item -> CapabilityJson.evidenceId("ev-compare", context.task().inputHash() + "|" + item.fundCode().value()))
                .toList();
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, result), evidence);
    }

    
    /** 执行该 Agent 运行时组件中的 required 操作。 */
    private String required(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(name + " is required");
        return String.valueOf(value);
    }
}
