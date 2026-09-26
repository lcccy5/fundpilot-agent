package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.FundComparisonUseCase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 按任务输入比较至少两只基金，并把比较结果写成产物。
 * 基金代码不足两个或日期、净值口径缺失时拒绝，不调用比较用例。
 */
public final class FundComparisonCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundComparisonUseCase comparison;
    private final ObjectMapper mapper;

    /**
     * 注入比较用例和 JSON 映射器。
     * 依赖为空时构造成功，执行到解析或比较时才会失败。
     */
    public FundComparisonCapabilityExecutor(FundComparisonUseCase comparison, ObjectMapper mapper) {
        this.comparison = comparison;
        this.mapper = mapper;
    }

    /**
     * 返回基金比较能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "FUND_COMPARE";
    }

    /**
     * 解析基金代码和区间后执行比较，并为每只基金生成证据标识。
     * 输入不是合法 JSON、代码少于两个、必填字段为空或日期无法解析时抛出非法参数。
     */
    @Override
    @SuppressWarnings("unchecked")
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        Map<String, Object> input = CapabilityJson.input(mapper, context.task().inputJson());
        Object values = input.get("fundCodes");
        if (!(values instanceof List<?> raw) || raw.size() < 2) {
            throw new IllegalArgumentException("fundCodes must contain at least two values");
        }
        List<String> codes = raw.stream().map(String::valueOf).toList();
        var result = comparison.compare(
                codes,
                LocalDate.parse(required(input, "startDate")),
                LocalDate.parse(required(input, "endDate")),
                required(input, "navBasis"));
        List<String> evidence = result.funds().stream()
                .map(item -> CapabilityJson.evidenceId(
                        "ev-compare",
                        context.task().inputHash() + "|" + item.fundCode().value()))
                .toList();
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, result), evidence);
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
