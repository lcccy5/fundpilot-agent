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

/** 实现 FundProfileTool 所代表的 Agent 运行时职责。 */
public class FundProfileTool {
    public static final String NAME = "get_fund_profile";
    private final FundQueryUseCase useCase; private final Validator validator;
    
    /** 执行该 Agent 运行时组件中的 FundProfileTool 操作。 */
    public FundProfileTool(FundQueryUseCase useCase, Validator validator){this.useCase=useCase;this.validator=validator;}

    @Tool(name=NAME, description="查询一只中国公募基金的名称、类型、管理公司、基金经理、数据来源和数据新鲜度。基金代码必须是6位数字；不能用于计算收益或比较基金。")
    
    /** 获取当前 Agent 操作所需的 getProfile 结果。 */
    public FundToolEnvelope<FundProfileResult> getProfile(ProfileInput input, ToolContext context) {
        AgentExecutionTrace trace=FundToolSupport.trace(context); var call=trace.begin(NAME,input);
        try { FundToolSupport.validate(validator,input); FundProfileResult result=trace.call(call,()->useCase.getProfile(input.fundCode()));
            var evidence=new EvidenceReference("ev-profile-"+UUID.randomUUID(),"FUND_PROFILE",result.fundCode(),null,null,null,
                    result.dataSource(),null,null,result.collectedAt());
            trace.success(call,evidence,result); return FundToolEnvelope.success(NAME,result,evidence,
                    "STALE".equals(result.freshness())?List.of("FUND_PROFILE_STALE"):List.of());
        } catch(ConstraintViolationException ex){trace.failure(call,"INVALID_ARGUMENT");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"INVALID_ARGUMENT",FundToolSupport.validationMessage(ex));}
        catch(RuntimeException ex){trace.failure(call,"FUND_PROFILE_FAILED");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"FUND_PROFILE_FAILED",FundToolSupport.safeMessage(ex));}
    }
    
    /** 在 Agent 运行时边界间传递 ProfileInput 数据的不可变值对象。 */
    public record ProfileInput(@Pattern(regexp="\\d{6}") String fundCode) {}
}
