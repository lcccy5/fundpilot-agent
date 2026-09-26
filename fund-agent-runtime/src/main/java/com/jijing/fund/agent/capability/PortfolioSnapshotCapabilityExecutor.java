package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按任务上的所有者读取组合快照。身份和组合标识只来自服务端，不读取模型输入。
 * 所有者账户不存在时失败，不返回空组合冒充成功。
 */
public final class PortfolioSnapshotCapabilityExecutor implements AgentCapabilityExecutor {
    private final PortfolioUseCase portfolios;
    private final UserAccountRepository accounts;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 使用系统 UTC 时钟创建执行器。
     * 依赖为空时构造成功，执行时才会失败。
     */
    public PortfolioSnapshotCapabilityExecutor(
            PortfolioUseCase portfolios,
            UserAccountRepository accounts,
            ObjectMapper mapper) {
        this(portfolios, accounts, mapper, Clock.systemUTC());
    }

    /**
     * 使用调用方提供的时钟创建执行器，便于固定快照日期。
     * 时钟为空时构造成功，读取当前日期时才会失败。
     */
    public PortfolioSnapshotCapabilityExecutor(
            PortfolioUseCase portfolios,
            UserAccountRepository accounts,
            ObjectMapper mapper,
            Clock clock) {
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 返回组合快照能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "PORTFOLIO_SNAPSHOT";
    }

    /**
     * 列出所有者的组合；若存在组合，再取标识最小的一只做估值。
     * 账户不存在时抛出非法状态；没有任何组合时只返回数量和日期，不视为失败。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        UserId owner = new UserId(task.ownerUserId());
        var account = accounts.findById(owner)
                .orElseThrow(() -> new IllegalStateException("task owner account not found"));
        var actor = new AuthenticatedUser(owner, account.roles(), "agent-run:" + task.runId());
        var available = portfolios.list(actor);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("portfolioCount", available.size());
        LocalDate asOf = LocalDate.now(clock);
        output.put("asOf", asOf.toString());
        if (!available.isEmpty()) {
            var portfolio = available.stream()
                    .sorted(Comparator.comparing(item -> item.portfolioId().value()))
                    .findFirst()
                    .orElseThrow();
            output.put("portfolioId", portfolio.portfolioId().value());
            output.put("valuation", portfolios.valuation(actor, portfolio.portfolioId(), asOf));
        }
        List<String> evidence = List.of(
                CapabilityJson.evidenceId("ev-portfolio", task.inputHash() + "|" + owner.value()));
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, output), evidence);
    }
}
