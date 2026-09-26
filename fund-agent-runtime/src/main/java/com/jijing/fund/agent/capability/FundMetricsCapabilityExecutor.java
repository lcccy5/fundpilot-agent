package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 按任务输入计算单只基金的区间指标，并生成一条指标证据。
 * 必填字段缺失或日期无法解析时拒绝，不调用指标查询。
 */
public final class FundMetricsCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundMetricsQueryUseCase metrics;
    private final ObjectMapper mapper;

    /**
     * 注入指标查询和 JSON 映射器。
     * 依赖为空时构造成功，执行时才会失败。
     */
    public FundMetricsCapabilityExecutor(FundMetricsQueryUseCase metrics, ObjectMapper mapper) {
        this.metrics = metrics;
        this.mapper = mapper;
    }

    /**
     * 返回基金指标查询能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "FUND_METRICS_QUERY";
    }

    /**
     * 计算指标并把证据标识写入执行结果。
     * 输入不是合法 JSON、必填字段为空或日期无法解析时抛出非法参数；查询失败时原样传出。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        Map<String, Object> input = CapabilityJson.input(mapper, context.task().inputJson());
        String fundCode = required(input, "fundCode");
        LocalDate start = LocalDate.parse(required(input, "startDate"));
        LocalDate end = LocalDate.parse(required(input, "endDate"));
        String navBasis = required(input, "navBasis");
        var result = metrics.calculate(fundCode, start, end, navBasis);
        String evidenceId = CapabilityJson.evidenceId("ev-metrics", context.task().inputHash());
        EvidenceReference evidence = new EvidenceReference(
                evidenceId,
                "FUND_METRICS",
                result.fundCode().value(),
                result.actualStartDate(),
                result.actualEndDate(),
                result.navBasis().name(),
                null,
                result.dataVersion(),
                result.algorithmVersion(),
                result.calculatedAt() == null ? Instant.now() : result.calculatedAt());
        return new CapabilityExecutionResult(
                CapabilityJson.artifactUri(mapper, context, result),
                List.of(evidence.evidenceId()));
    }

    /**
     * 读取必填文本字段。
     * 字段缺失或只有空白时抛出非法参数。
     */
    private String required(Map<String, Object> input, String name) {
        Object value = input.get(name);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return String.valueOf(value);
    }
}
