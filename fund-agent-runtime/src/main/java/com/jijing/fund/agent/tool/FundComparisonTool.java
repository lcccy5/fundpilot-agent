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

/** 实现 FundComparisonTool 所代表的 Agent 运行时职责。 */
public class FundComparisonTool {
    public static final String NAME="compare_fund_metrics";
    private final FundComparisonUseCase useCase;private final Validator validator;
    
    /** 执行该 Agent 运行时组件中的 FundComparisonTool 操作。 */
    public FundComparisonTool(FundComparisonUseCase useCase,Validator validator){this.useCase=useCase;this.validator=validator;}
    @Tool(name=NAME,description="在相同实际净值区间和相同净值口径下比较2到10只中国公募基金，返回累计收益、波动率、最大回撤绝对值和夏普比率排名。只提供历史指标排序，不代表购买建议。")
    
    /** 执行该 Agent 运行时组件中的 compare 操作。 */
    public FundToolEnvelope<FundComparisonResult> compare(ComparisonInput input,ToolContext context){
        AgentExecutionTrace trace=FundToolSupport.trace(context);var call=trace.begin(NAME,input);
        try{FundToolSupport.validate(validator,input);if(input.startDate().isAfter(input.endDate()))throw new IllegalArgumentException("startDate must not be after endDate");
            FundComparisonResult result=trace.call(call,()->useCase.compare(input.fundCodes(),input.startDate(),input.endDate(),input.navBasis()));
            List<EvidenceReference> evidence=result.funds().stream().map(f->new EvidenceReference("ev-compare-"+UUID.randomUUID(),"FUND_COMPARISON",f.fundCode().value(),result.commonStartDate(),result.commonEndDate(),result.navBasis().name(),null,f.dataVersion(),f.algorithmVersion(),f.calculatedAt())).toList();
            trace.success(call,evidence,result);
            return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.SUCCESS,result,evidence,List.of("HISTORICAL_RANKING_NOT_INVESTMENT_ADVICE"),null,null);
        }catch(ConstraintViolationException|IllegalArgumentException ex){trace.failure(call,"INVALID_ARGUMENT");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"INVALID_ARGUMENT",FundToolSupport.safeMessage(ex));}
        catch(RuntimeException ex){trace.failure(call,"COMPARISON_NOT_READY");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"COMPARISON_NOT_READY",FundToolSupport.safeMessage(ex));}
    }
    
    /** 在 Agent 运行时边界间传递 ComparisonInput 数据的不可变值对象。 */
    public record ComparisonInput(@NotNull @Size(min=2,max=10) List<@Pattern(regexp="\\d{6}") String> fundCodes,@NotNull LocalDate startDate,@NotNull LocalDate endDate,String navBasis){}
}
