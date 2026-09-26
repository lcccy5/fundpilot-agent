package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.analytics.model.*;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 计算单只公募基金在指定区间的收益、波动、回撤和夏普。不可用指标写入告警，禁止当成 0。
 * 参数不合法时返回可修正错误；用例失败时返回数据未就绪。缺少执行轨迹时在进入计算前抛出 IllegalStateException。
 */
public class FundMetricsTool {
    public static final String NAME = "calculate_fund_metrics";
    private final FundMetricsQueryUseCase useCase;
    private final Validator validator;

    /**
     * 绑定指标用例和校验器。本构造器不检查空引用，后续调用才会失败。
     */
    public FundMetricsTool(FundMetricsQueryUseCase useCase, Validator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    /**
     * 校验基金代码和日期后计算指标，并附带一条基金指标证据。
     * 约束违反或起止日期颠倒时记录 INVALID_ARGUMENT 并返回 USER_CORRECTABLE；
     * 其他运行时失败记录 METRICS_NOT_READY 并返回 DATA_NOT_READY。轨迹预算耗尽时异常直接抛出。
     */
    @Tool(name = NAME, description = "计算一只中国公募基金指定区间的累计收益、年化收益、年化波动率、最大回撤、夏普比率和上涨日占比。所有比率用小数表示，0.12代表12%。指标不可用时返回原因，禁止当作0。")
    public FundToolEnvelope<FundMetrics> calculate(MetricsInput input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            FundToolSupport.validate(validator, input);
            if (input.startDate().isAfter(input.endDate())) {
                throw new IllegalArgumentException("startDate must not be after endDate");
            }
            FundMetrics result = trace.call(call, () -> useCase.calculate(input.fundCode(), input.startDate(), input.endDate(), input.navBasis()));
            var evidence = new EvidenceReference("ev-metrics-" + UUID.randomUUID(), "FUND_METRICS", result.fundCode().value(),
                    result.actualStartDate(), result.actualEndDate(), result.navBasis().name(), null, result.dataVersion(),
                    result.algorithmVersion(), result.calculatedAt());
            List<String> warnings = new ArrayList<>();
            if (result.coverage().status() != CoverageStatus.COMPLETE) {
                warnings.add("COVERAGE_" + result.coverage().status());
            }
            collectUnavailable(warnings, "annualizedReturn", result.annualizedReturn());
            collectUnavailable(warnings, "annualizedVolatility", result.annualizedVolatility());
            collectUnavailable(warnings, "sharpeRatio", result.sharpeRatio());
            trace.success(call, evidence, result);
            return FundToolEnvelope.success(NAME, result, evidence, warnings);
        } catch (ConstraintViolationException | IllegalArgumentException ex) {
            trace.failure(call, "INVALID_ARGUMENT");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "INVALID_ARGUMENT", FundToolSupport.safeMessage(ex));
        } catch (RuntimeException ex) {
            trace.failure(call, "METRICS_NOT_READY");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "METRICS_NOT_READY", FundToolSupport.safeMessage(ex));
        }
    }

    /**
     * 指标不可用时把名称和原因写入告警。状态为可用时不修改告警列表。
     */
    private void collectUnavailable(List<String> warnings, String metric, MetricValue value) {
        if (value.status() == MetricStatus.UNAVAILABLE) {
            warnings.add(metric + ":" + value.unavailableReason());
        }
    }

    /**
     * 指标计算入参。基金代码必须是 6 位数字，起止日期不能为空。
     * 约束由 Validator 执行；日期先后由 calculate 再检查，不在此构造器失败。
     */
    public record MetricsInput(@Pattern(regexp = "\\d{6}") String fundCode, @NotNull LocalDate startDate,
            @NotNull LocalDate endDate, String navBasis) {}
}
