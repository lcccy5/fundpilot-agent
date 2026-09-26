package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundProfileResult;
import jakarta.validation.*;
import jakarta.validation.constraints.Pattern;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 查询单只公募基金的名称、类型、公司和经理等档案。不计算收益。
 * 基金代码约束失败时返回可修正错误；查询失败，包括非法参数异常，返回档案失败。缺少执行轨迹时直接抛出 IllegalStateException。
 */
public class FundProfileTool {
    public static final String NAME = "get_fund_profile";
    private final FundQueryUseCase useCase;
    private final Validator validator;

    /**
     * 绑定档案查询用例和校验器。不在构造时拒绝空引用。
     */
    public FundProfileTool(FundQueryUseCase useCase, Validator validator) {
        this.useCase = useCase;
        this.validator = validator;
    }

    /**
     * 校验 6 位基金代码后读取档案，并附带一条档案证据。数据标记为 STALE 时增加 FUND_PROFILE_STALE 告警，仍算成功。
     * 约束违反记录 INVALID_ARGUMENT；其他运行时失败记录 FUND_PROFILE_FAILED 并返回 DATA_NOT_READY。
     */
    @Tool(name = NAME, description = "查询一只中国公募基金的名称、类型、管理公司、基金经理、数据来源和数据新鲜度。基金代码必须是6位数字；不能用于计算收益或比较基金。")
    public FundToolEnvelope<FundProfileResult> getProfile(ProfileInput input, ToolContext context) {
        AgentExecutionTrace trace = FundToolSupport.trace(context);
        var call = trace.begin(NAME, input);
        try {
            FundToolSupport.validate(validator, input);
            FundProfileResult result = trace.call(call, () -> useCase.getProfile(input.fundCode()));
            var evidence = new EvidenceReference("ev-profile-" + UUID.randomUUID(), "FUND_PROFILE", result.fundCode(), null, null, null,
                    result.dataSource(), null, null, result.collectedAt());
            trace.success(call, evidence, result);
            return FundToolEnvelope.success(NAME, result, evidence,
                    "STALE".equals(result.freshness()) ? List.of("FUND_PROFILE_STALE") : List.of());
        } catch (ConstraintViolationException ex) {
            trace.failure(call, "INVALID_ARGUMENT");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.USER_CORRECTABLE, null, List.of(),
                    List.of(), "INVALID_ARGUMENT", FundToolSupport.validationMessage(ex));
        } catch (RuntimeException ex) {
            trace.failure(call, "FUND_PROFILE_FAILED");
            return new FundToolEnvelope<>(NAME, "fund-tools-v1", ToolResultStatus.DATA_NOT_READY, null, List.of(),
                    List.of(), "FUND_PROFILE_FAILED", FundToolSupport.safeMessage(ex));
        }
    }

    /**
     * 档案查询入参。基金代码必须匹配 6 位数字；不匹配时由 Validator 拒绝，构造器本身不抛错。
     */
    public record ProfileInput(@Pattern(regexp = "\\d{6}") String fundCode) {}
}
