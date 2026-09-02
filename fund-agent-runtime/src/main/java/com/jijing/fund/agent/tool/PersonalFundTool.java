package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.portfolio.*;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.portfolio.PortfolioId;
import jakarta.validation.constraints.Pattern;
import java.time.*;import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/** Personal tools obtain identity exclusively from server-created ToolContext. */
public class PersonalFundTool {
    private final WatchlistUseCase watchlists;private final PortfolioUseCase portfolios;
    
    /** 执行该 Agent 运行时组件中的 PersonalFundTool 操作。 */
    public PersonalFundTool(WatchlistUseCase watchlists,PortfolioUseCase portfolios){this.watchlists=watchlists;this.portfolios=portfolios;}
    @Tool(name="get_my_watchlist",description="读取当前登录用户的自选分组和基金代码。不能读取其他用户。")
    
    /** 执行该 Agent 运行时组件中的 watchlist 操作。 */
    public FundToolEnvelope<?> watchlist(ToolContext context){var trace=FundToolSupport.trace(context);var call=trace.begin("get_my_watchlist",Map.of());try{var user=FundToolSupport.user(context);var result=trace.call(call,()->watchlists.list(user));var e=evidence(user.userId().value(),"WATCHLIST",null);trace.success(call,e,result);return FundToolEnvelope.success("get_my_watchlist",result,e,List.of());}catch(RuntimeException x){trace.failure(call,"PERSONAL_DATA_UNAVAILABLE");return unavailable("get_my_watchlist",x);}}
    @Tool(name="get_my_portfolio",description="读取当前登录用户的组合、持仓和最新估值。portfolioId 为空时返回第一个组合。")
    
    /** 执行该 Agent 运行时组件中的 portfolio 操作。 */
    public FundToolEnvelope<?> portfolio(PortfolioInput input,ToolContext context){var trace=FundToolSupport.trace(context);var call=trace.begin("get_my_portfolio",input);try{var user=FundToolSupport.user(context);var p=choose(user,input.portfolioId());var result=trace.call(call,()->Map.of("portfolio",p,"positions",portfolios.positions(user,p.portfolioId()),"valuation",portfolios.valuation(user,p.portfolioId(),input.asOfDate())));var e=evidence(user.userId().value(),"PORTFOLIO",p.portfolioId().value());trace.success(call,e,result);return FundToolEnvelope.success("get_my_portfolio",result,e,List.of());}catch(RuntimeException x){trace.failure(call,"PERSONAL_DATA_UNAVAILABLE");return unavailable("get_my_portfolio",x);}}
    @Tool(name="calculate_my_return",description="计算当前用户组合的资金加权收益率（XIRR）、成本和市值；没有足够现金流或估值时会明确返回不可用，不是收益保证。")
    
    /** 执行该 Agent 运行时组件中的 returns 操作。 */
    public FundToolEnvelope<?> returns(PortfolioInput input,ToolContext context){var trace=FundToolSupport.trace(context);var call=trace.begin("calculate_my_return",input);try{var user=FundToolSupport.user(context);var p=choose(user,input.portfolioId());var result=trace.call(call,()->portfolios.returns(user,p.portfolioId(),input.asOfDate()));var e=evidence(user.userId().value(),"PORTFOLIO_RETURN",p.portfolioId().value());trace.success(call,e,result);return FundToolEnvelope.success("calculate_my_return",result,e,result.warnings());}catch(RuntimeException x){trace.failure(call,"PERSONAL_DATA_UNAVAILABLE");return unavailable("calculate_my_return",x);}}
    @Tool(name="analyze_my_portfolio_risk",description="分析当前用户组合的集中度和净值数据覆盖状态，不提供买卖指令。")
    
    /** 执行该 Agent 运行时组件中的 risk 操作。 */
    public FundToolEnvelope<?> risk(PortfolioInput input,ToolContext context){var trace=FundToolSupport.trace(context);var call=trace.begin("analyze_my_portfolio_risk",input);try{var user=FundToolSupport.user(context);var p=choose(user,input.portfolioId());var v=trace.call(call,()->portfolios.risk(user,p.portfolioId(),input.asOfDate()));var result=Map.of("risk",v,"coverage",v.coverage());var e=evidence(user.userId().value(),"PORTFOLIO_RISK",p.portfolioId().value());trace.success(call,e,result);return FundToolEnvelope.success("analyze_my_portfolio_risk",result,e,v.warnings());}catch(RuntimeException x){trace.failure(call,"PERSONAL_DATA_UNAVAILABLE");return unavailable("analyze_my_portfolio_risk",x);}}
    @Tool(name="compare_watchlist_funds",description="读取当前用户自选中的基金代码，供公开基金比较工具继续研究。")
    
    /** 执行该 Agent 运行时组件中的 compare 操作。 */
    public FundToolEnvelope<?> compare(ToolContext context){var trace=FundToolSupport.trace(context);var call=trace.begin("compare_watchlist_funds",Map.of());try{var user=FundToolSupport.user(context);var result=trace.call(call,()->watchlists.list(user).stream().flatMap(g->g.items().stream()).map(i->i.fundCode().value()).distinct().toList());var e=evidence(user.userId().value(),"WATCHLIST_COMPARE",null);trace.success(call,e,result);return FundToolEnvelope.success("compare_watchlist_funds",result,e,List.of());}catch(RuntimeException x){trace.failure(call,"PERSONAL_DATA_UNAVAILABLE");return unavailable("compare_watchlist_funds",x);}}
    
    /** 执行该 Agent 运行时组件中的 choose 操作。 */
    private com.jijing.fund.domain.portfolio.UserPortfolio choose(com.jijing.fund.domain.identity.AuthenticatedUser u,String id){if(id!=null&&!id.isBlank())return portfolios.list(u).stream().filter(p->p.portfolioId().value().equals(id)).findFirst().orElseThrow(()->new PortfolioException("portfolio not found"));return portfolios.list(u).stream().findFirst().orElseThrow(()->new PortfolioException("no portfolio available"));}
    /** Evidence IDs are deterministic for an owner-scoped current snapshot; no user identifier is exposed to the model. */
    private static EvidenceReference evidence(String user,String type,String portfolio){String resource=portfolio==null?"CURRENT":portfolio;return new EvidenceReference(type+":"+resource+":SNAPSHOT:CURRENT",type,resource,null,null,"USER_CONFIRMED",null,"portfolio-position-v1","fund-agent-v3",Instant.now());}
    
    /** 执行该 Agent 运行时组件中的 unavailable 操作。 */
    private static FundToolEnvelope<?> unavailable(String name,RuntimeException x){return new FundToolEnvelope<>(name,"fund-tools-v3",ToolResultStatus.DATA_NOT_READY,null,List.of(),List.of(),"PERSONAL_DATA_UNAVAILABLE",FundToolSupport.safeMessage(x));}
    
    /** 在 Agent 运行时边界间传递 PortfolioInput 数据的不可变值对象。 */
    public record PortfolioInput(@Pattern(regexp="^$|[0-9a-fA-F-]{36}$") String portfolioId,LocalDate asOfDate){}
}
