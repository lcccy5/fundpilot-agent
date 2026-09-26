package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.dto.FundComparisonResult;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 在同一净值口径和实际重叠区间上比较 2 到 10 只基金的历史指标排名。结果不是购买建议。
 * 参数不合法时返回可修正错误；比较用例失败时返回数据未就绪。缺少执行轨迹时直接抛出 IllegalStateException。
 */
public class FundComparisonTool {
    public static final String NAME = "compare_fund_metrics";
    private final FundComparisonUseCase useCase;
    private final Validator validator;

    /**
     * 绑定比较用例和校验器。不在构造时拒绝空引用。
     */
    public FundComparisonTool(FundComparisonUseCase useCase, Validator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    /**
     * 校验基金列表和日期后比较指标，并为每只基金生成一条比较证据。
     * 成功时固定附加历史排名不是投资建议的告警。约束违反或日期颠倒记录 INVALID_ARGUMENT；其他运行时失败记录 COMPARISON_NOT_READY。
     */
    @Tool(name = NAME, description = "在相同实际净值区间和相同净值口径下比较2到10只中国公募基金，返回累计收益、波动率、最大回撤绝对值和夏普比率排名。只提供历史指标排序，不代表购买建议。")
    public FundToolEnvelope<FundComparisonResult> compare(ComparisonInput input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            FundToolSupport.validate(validator, input);
            if (input.startDate().isAfter(input.endDate())) {
                throw new IllegalArgumentException("startDate must not be after endDate");
            }
            FundComparisonResult result = trace.call(call, () -> useCase.compare(input.fundCodes(), input.startDate(), input.endDate(), input.navBasis()));
            List<EvidenceReference> evidence = result.funds().stream().map(f -> new EvidenceReference("ev-compare-" + UUID.randomUUID(),
                    "FUND_COMPARISON", f.fundCode().value(), result.commonStartDate(), result.commonEndDate(), result.navBasis().name(),
                    null, f.dataVersion(), f.algorithmVersion(), f.calculatedAt())).toList();
            trace.success(call, evidence, result);
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.SUCCESS, result, evidence,
                    List.of("HISTORICAL_RANKING_NOT_INVESTMENT_ADVICE"), null, null);
        } catch (ConstraintViolationException | IllegalArgumentException ex) {
            trace.failure(call, "INVALID_ARGUMENT");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "INVALID_ARGUMENT", FundToolSupport.safeMessage(ex));
        } catch (RuntimeException ex) {
            trace.failure(call, "COMPARISON_NOT_READY");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "COMPARISON_NOT_READY", FundToolSupport.safeMessage(ex));
        }
    }

    /**
     * 比较入参。基金代码列表长度为 2 到 10，且每个代码为 6 位数字。日期先后由 compare 检查，构造器不抛错。
     */
    public record ComparisonInput(@NotNull @Size(min = 2, max = 10) List<@Pattern(regexp = "\\d{6}") String> fundCodes,
            @NotNull LocalDate startDate, @NotNull LocalDate endDate, String navBasis) {}
}
