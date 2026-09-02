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

/** 实现 FundNavTool 所代表的 Agent 运行时职责。 */
public class FundNavTool {
    public static final String NAME="get_fund_nav_history";
    private final FundQueryUseCase useCase; private final Validator validator;
    
    /** 执行该 Agent 运行时组件中的 FundNavTool 操作。 */
    public FundNavTool(FundQueryUseCase useCase,Validator validator){this.useCase=useCase;this.validator=validator;}
    @Tool(name=NAME,description="查询一只中国公募基金指定日期区间的历史单位净值和累计净值。用于回答具体日期净值问题，不应让模型根据净值自行计算收益、回撤或夏普比率。")
    
    /** 获取当前 Agent 操作所需的 getNav 结果。 */
    public FundToolEnvelope<FundNavHistoryResult> getNav(NavInput input, ToolContext context){
        AgentExecutionTrace trace=FundToolSupport.trace(context);var call=trace.begin(NAME,input);
        try{FundToolSupport.validate(validator,input);if(input.startDate().isAfter(input.endDate()))throw new IllegalArgumentException("startDate must not be after endDate");
            FundNavHistoryResult result=trace.call(call,()->useCase.getNavHistory(input.fundCode(),input.startDate(),input.endDate()));
            LocalDate actualStart=result.items().isEmpty()?null:result.items().getFirst().navDate();LocalDate actualEnd=result.items().isEmpty()?null:result.items().getLast().navDate();
            var evidence=new EvidenceReference("ev-nav-"+UUID.randomUUID(),"FUND_NAV",result.fundCode(),actualStart,actualEnd,null,result.dataSource(),null,null,null);
            trace.success(call,evidence,result);return FundToolEnvelope.success(NAME,result,evidence,result.items().isEmpty()?List.of("NAV_DATA_EMPTY"):List.of());
        }catch(ConstraintViolationException|IllegalArgumentException ex){trace.failure(call,"INVALID_ARGUMENT");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.USER_CORRECTABLE,null,List.of(),List.of(),"INVALID_ARGUMENT",FundToolSupport.safeMessage(ex));}
        catch(RuntimeException ex){trace.failure(call,"NAV_QUERY_FAILED");return new FundToolEnvelope<>(NAME,"fund-tools-v1",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"NAV_QUERY_FAILED",FundToolSupport.safeMessage(ex));}
    }
    
    /** 在 Agent 运行时边界间传递 NavInput 数据的不可变值对象。 */
    public record NavInput(@Pattern(regexp="\\d{6}") String fundCode,@NotNull LocalDate startDate,@NotNull LocalDate endDate){}
}
