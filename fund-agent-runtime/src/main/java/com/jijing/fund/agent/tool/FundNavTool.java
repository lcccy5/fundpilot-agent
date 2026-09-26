package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundNavHistoryResult;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 查询单只基金在日期区间内的历史单位净值和累计净值。不在这里计算收益或回撤。
 * 参数不合法时返回可修正错误；查询失败时返回净值查询失败。缺少执行轨迹时直接抛出 IllegalStateException。
 */
public class FundNavTool {
    public static final String NAME = "get_fund_nav_history";
    private final FundQueryUseCase useCase;
    private final Validator validator;

    /**
     * 绑定净值查询用例和校验器。不在构造时拒绝空引用。
     */
    public FundNavTool(FundQueryUseCase useCase, Validator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    /**
     * 校验代码和日期后读取净值序列。序列为空时仍返回成功，并附加 NAV_DATA_EMPTY，不把空序列当成查询失败。
     * 约束违反或日期颠倒时记录 INVALID_ARGUMENT；其他运行时失败记录 NAV_QUERY_FAILED。
     */
    @Tool(name = NAME, description = "查询一只中国公募基金指定日期区间的历史单位净值和累计净值。用于回答具体日期净值问题，不应让模型根据净值自行计算收益、回撤或夏普比率。")
    public FundToolEnvelope<FundNavHistoryResult> getNav(NavInput input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            FundToolSupport.validate(validator, input);
            if (input.startDate().isAfter(input.endDate())) {
                throw new IllegalArgumentException("startDate must not be after endDate");
            }
            FundNavHistoryResult result = trace.call(call, () -> useCase.getNavHistory(input.fundCode(), input.startDate(), input.endDate()));
            LocalDate actualStart = result.items().isEmpty() ? null : result.items().getFirst().navDate();
            LocalDate actualEnd = result.items().isEmpty() ? null : result.items().getLast().navDate();
            var evidence = new EvidenceReference("ev-nav-" + UUID.randomUUID(), "FUND_NAV", result.fundCode(), actualStart, actualEnd, null,
                    result.dataSource(), null, null, null);
            trace.success(call, evidence, result);
            return FundToolEnvelope.success(NAME, result, evidence, result.items().isEmpty() ? List.of("NAV_DATA_EMPTY") : List.of());
        } catch (ConstraintViolationException | IllegalArgumentException ex) {
            trace.failure(call, "INVALID_ARGUMENT");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "INVALID_ARGUMENT", FundToolSupport.safeMessage(ex));
        } catch (RuntimeException ex) {
            trace.failure(call, "NAV_QUERY_FAILED");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "NAV_QUERY_FAILED", FundToolSupport.safeMessage(ex));
        }
    }

    /**
     * 净值区间入参。基金代码必须是 6 位数字，起止日期不能为空。日期先后由 getNav 检查，构造器不抛错。
     */
    public record NavInput(@Pattern(regexp = "\\d{6}") String fundCode, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}
}
