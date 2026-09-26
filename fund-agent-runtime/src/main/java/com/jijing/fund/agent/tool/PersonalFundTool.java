package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.application.portfolio.*;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import jakarta.validation.constraints.Pattern;
import java.time.*;
import java.util.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

/**
 * 读取当前登录用户的自选、持仓和收益。身份只来自服务端写入的 ToolContext，工具参数里不能传用户标识。
 * 个人数据访问失败时返回 DATA_NOT_READY，不把异常细节以外的内容扩展成投资结论。缺少执行轨迹时直接抛出 IllegalStateException。
 */
public class PersonalFundTool {
    private final WatchlistUseCase watchlists;
    private final PortfolioUseCase portfolios;

    /**
     * 绑定自选和组合用例。不在构造时拒绝空引用。
     */
    public PersonalFundTool(WatchlistUseCase watchlists, PortfolioUseCase portfolios) {
        this.watchlists = watchlists;
        this.portfolios = portfolios;
    }

    /**
     * 列出当前用户的自选分组。用户上下文缺失或用例抛出运行时异常时，记录 PERSONAL_DATA_UNAVAILABLE 并返回未就绪信封。
     */
    @Tool(name = "get_my_watchlist", description = "读取当前登录用户的自选分组和基金代码。不能读取其他用户。")
    public FundToolEnvelope<?> watchlist(ToolContext context) {
        var trace = FundToolSupport.trace(context);
        var call = trace.begin("get_my_watchlist", Map.of());
        try {
            var user = FundToolSupport.user(context);
            var result = trace.call(call, () -> watchlists.list(user));
            var e = evidence(user.userId().value(), "WATCHLIST", null);
            trace.success(call, e, result);
            return FundToolEnvelope.success("get_my_watchlist", result, e, List.of());
        } catch (RuntimeException x) {
            trace.failure(call, "PERSONAL_DATA_UNAVAILABLE");
            return unavailable("get_my_watchlist", x);
        }
    }

    /**
     * 读取指定组合或用户的第一个组合，连同持仓和估值返回。组合不存在、没有组合或用例失败时返回个人数据不可用。
     */
    @Tool(name = "get_my_portfolio", description = "读取当前登录用户的组合、持仓和最新估值。portfolioId 为空时返回第一个组合。")
    public FundToolEnvelope<?> portfolio(PortfolioInput input, ToolContext context) {
        var trace = FundToolSupport.trace(context);
        var call = trace.begin("get_my_portfolio", input);
        try {
            var user = FundToolSupport.user(context);
            var p = choose(user, input.portfolioId());
            var result = trace.call(call, () -> Map.of("portfolio", p, "positions", portfolios.positions(user, p.portfolioId()),
                    "valuation", portfolios.valuation(user, p.portfolioId(), input.asOfDate())));
            var e = evidence(user.userId().value(), "PORTFOLIO", p.portfolioId().value());
            trace.success(call, e, result);
            return FundToolEnvelope.success("get_my_portfolio", result, e, List.of());
        } catch (RuntimeException x) {
            trace.failure(call, "PERSONAL_DATA_UNAVAILABLE");
            return unavailable("get_my_portfolio", x);
        }
    }

    /**
     * 计算当前用户组合的资金加权收益。现金流或估值不足时由用例通过告警或异常表达，本方法不把失败改写成收益保证。
     * 运行时失败统一返回个人数据不可用。
     */
    @Tool(name = "calculate_my_return", description = "计算当前用户组合的资金加权收益率（XIRR）、成本和市值；没有足够现金流或估值时会明确返回不可用，不是收益保证。")
    public FundToolEnvelope<?> returns(PortfolioInput input, ToolContext context) {
        var trace = FundToolSupport.trace(context);
        var call = trace.begin("calculate_my_return", input);
        try {
            var user = FundToolSupport.user(context);
            var p = choose(user, input.portfolioId());
            var result = trace.call(call, () -> portfolios.returns(user, p.portfolioId(), input.asOfDate()));
            var e = evidence(user.userId().value(), "PORTFOLIO_RETURN", p.portfolioId().value());
            trace.success(call, e, result);
            return FundToolEnvelope.success("calculate_my_return", result, e, result.warnings());
        } catch (RuntimeException x) {
            trace.failure(call, "PERSONAL_DATA_UNAVAILABLE");
            return unavailable("calculate_my_return", x);
        }
    }

    /**
     * 分析当前用户组合的集中度和净值覆盖。不生成买卖指令。运行时失败返回个人数据不可用。
     */
    @Tool(name = "analyze_my_portfolio_risk", description = "分析当前用户组合的集中度和净值数据覆盖状态，不提供买卖指令。")
    public FundToolEnvelope<?> risk(PortfolioInput input, ToolContext context) {
        var trace = FundToolSupport.trace(context);
        var call = trace.begin("analyze_my_portfolio_risk", input);
        try {
            var user = FundToolSupport.user(context);
            var p = choose(user, input.portfolioId());
            var v = trace.call(call, () -> portfolios.risk(user, p.portfolioId(), input.asOfDate()));
            var result = Map.of("risk", v, "coverage", v.coverage());
            var e = evidence(user.userId().value(), "PORTFOLIO_RISK", p.portfolioId().value());
            trace.success(call, e, result);
            return FundToolEnvelope.success("analyze_my_portfolio_risk", result, e, v.warnings());
        } catch (RuntimeException x) {
            trace.failure(call, "PERSONAL_DATA_UNAVAILABLE");
            return unavailable("analyze_my_portfolio_risk", x);
        }
    }

    /**
     * 读取当前用户自选中的基金代码，供后续公开比较使用。自选读取失败时返回个人数据不可用，不返回其他用户的代码。
     */
    @Tool(name = "compare_watchlist_funds", description = "读取当前用户自选中的基金代码，供公开基金比较工具继续研究。")
    public FundToolEnvelope<?> compare(ToolContext context) {
        var trace = FundToolSupport.trace(context);
        var call = trace.begin("compare_watchlist_funds", Map.of());
        try {
            var user = FundToolSupport.user(context);
            var result = trace.call(call, () -> watchlists.list(user).stream().flatMap(g -> g.items().stream())
                    .map(i -> i.fundCode().value()).distinct().toList());
            var e = evidence(user.userId().value(), "WATCHLIST_COMPARE", null);
            trace.success(call, e, result);
            return FundToolEnvelope.success("compare_watchlist_funds", result, e, List.of());
        } catch (RuntimeException x) {
            trace.failure(call, "PERSONAL_DATA_UNAVAILABLE");
            return unavailable("compare_watchlist_funds", x);
        }
    }

    /**
     * 按组合标识选择用户自己的组合。标识为空时取列表中的第一个；找不到或列表为空时抛出 PortfolioException。
     */
    private UserPortfolio choose(AuthenticatedUser u, String id) {
        if (id != null && !id.isBlank()) {
            return portfolios.list(u).stream().filter(p -> p.portfolioId().value().equals(id)).findFirst()
                    .orElseThrow(() -> new PortfolioException("portfolio not found"));
        }
        return portfolios.list(u).stream().findFirst().orElseThrow(() -> new PortfolioException("no portfolio available"));
    }

    /**
     * 生成当前快照的证据编号。编号只含数据类型和组合资源，不把用户标识暴露给模型。
     */
    private static EvidenceReference evidence(String user, String type, String portfolio) {
        String resource = portfolio == null ? "CURRENT" : portfolio;
        return new EvidenceReference(type + ":" + resource + ":SNAPSHOT:CURRENT", type, resource, null, null, "USER_CONFIRMED",
                null, "portfolio-position-v1", "fund-agent-v3", Instant.now());
    }

    /**
     * 把个人数据失败收成 fund-tools-v3 未就绪信封。安全文案最多保留异常消息的前 300 字。
     */
    private static FundToolEnvelope<?> unavailable(String name, RuntimeException x) {
        return new FundToolEnvelope<>(name, "fund-tools-v3", ToolResultStatus.DATA_NOT_READY, null, List.of(), List.of(),
                "PERSONAL_DATA_UNAVAILABLE", FundToolSupport.safeMessage(x));
    }

    /**
     * 个人组合查询入参。组合标识可空；若填写，注解要求为空串或 36 位 UUID 形态。该注解不会由工具方法自身执行。
     */
    public record PortfolioInput(@Pattern(regexp = "^$|[0-9a-fA-F-]{36}$") String portfolioId, LocalDate asOfDate) {}
}
