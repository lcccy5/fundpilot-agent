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

/** 实现 FundMetricsTool 所代表的 Agent 运行时职责。 */
public class FundMetricsTool {
    public static final String NAME="calculate_fund_metrics";
    private final FundMetricsQueryUseCase useCase;private final Validator validator;
    
    /** 执行该 Agent 运行时组件中的 FundMetricsTool 操作。 */
    public FundMetricsTool(FundMetricsQueryUseCase useCase,Validator validator){this.useCase=useCase;this.validator=validator;}
    @Tool(name=NAME,description="计算一只中国公募基金指定区间的累计收益、年化收益、年化波动率、最大回撤、夏普比率和上涨日占比。所有比率用小数表示，0.12代表12%。指标不可用时返回原因，禁止当作0。")
    
    /** 执行该 Agent 运行时组件中的 calculate 操作。 */
    public FundToolEnvelope<FundMetrics> calculate(MetricsInput input,ToolContext context){
        AgentExecutionTrace trace=FundToolSupport.trace(context);var call=trace.begin(NAME,input);
        try{FundToolSupport.validate(validator,input);if(input.startDate().isAfter(input.endDate()))throw new IllegalArgumentException("startDate must not be after endDate");
            FundMetrics result=trace.call(call,()->useCase.calculate(input.fundCode(),input.startDate(),input.endDate(),input.navBasis()));
            var evidence=new EvidenceReference("ev-metrics-"+UUID.randomUUID(),"FUND_METRICS",result.fundCode().value(),result.actualStartDate(),result.actualEndDate(),result.navBasis().name(),null,result.dataVersion(),result.algorithmVersion(),result.calculatedAt());
            List<String>warnings=new ArrayList<>();if(result.coverage().status()!=CoverageStatus.COMPLETE)warnings.add("COVERAGE_"+result.coverage().status());
            collectUnavailable(warnings,"annualizedReturn",result.annualizedReturn());collectUnavailable(warnings,"annualizedVolatility",result.annualizedVolatility());collectUnavailable(warnings,"sharpeRatio",result.sharpeRatio());
            trace.success(call,evidence,result);return FundToolEnvelope.success(NAME,result,evidence,warnings);
        }catch(ConstraintViolationException|IllegalArgumentException ex){trace.failure(call,"INVALID_ARGUMENT");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"INVALID_ARGUMENT",FundToolSupport.safeMessage(ex));}
        catch(RuntimeException ex){trace.failure(call,"METRICS_NOT_READY");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"METRICS_NOT_READY",FundToolSupport.safeMessage(ex));}
    }
    
    /** 执行该 Agent 运行时组件中的 collectUnavailable 操作。 */
    private void collectUnavailable(List<String>warnings,String metric,MetricValue value){if(value.status()==MetricStatus.UNAVAILABLE)warnings.add(metric+":"+value.unavailableReason());}
    
    /** 在 Agent 运行时边界间传递 MetricsInput 数据的不可变值对象。 */
    public record MetricsInput(@Pattern(regexp="\\d{6}") String fundCode,@NotNull LocalDate startDate,@NotNull LocalDate endDate,String navBasis){}
}
