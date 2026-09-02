package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import java.time.LocalDate;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves the task owner on the server; no identity or portfolio id is taken from model input. */
public final class PortfolioSnapshotCapabilityExecutor implements AgentCapabilityExecutor {
    private final PortfolioUseCase portfolios;
    private final UserAccountRepository accounts;
    private final ObjectMapper mapper;
    private final Clock clock;

    
    /** 执行该 Agent 运行时组件中的 PortfolioSnapshotCapabilityExecutor 操作。 */
    public PortfolioSnapshotCapabilityExecutor(PortfolioUseCase portfolios, UserAccountRepository accounts, ObjectMapper mapper) {
        this(portfolios, accounts, mapper, Clock.systemUTC());
    }
    
    /** 执行该 Agent 运行时组件中的 PortfolioSnapshotCapabilityExecutor 操作。 */
    public PortfolioSnapshotCapabilityExecutor(PortfolioUseCase portfolios, UserAccountRepository accounts, ObjectMapper mapper, Clock clock) {
        this.portfolios = portfolios;
        this.accounts = accounts;
        this.mapper = mapper;
        this.clock = clock;
    }
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "PORTFOLIO_SNAPSHOT"; }

    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        UserId owner = new UserId(task.ownerUserId());
        var account = accounts.findById(owner).orElseThrow(() -> new IllegalStateException("task owner account not found"));
        var actor = new AuthenticatedUser(owner, account.roles(), "agent-run:" + task.runId());
        var available = portfolios.list(actor);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("portfolioCount", available.size());
        LocalDate asOf = LocalDate.now(clock);
        output.put("asOf", asOf.toString());
        if (!available.isEmpty()) {
            var portfolio = available.stream().sorted(java.util.Comparator.comparing(item -> item.portfolioId().value())).findFirst().orElseThrow();
            output.put("portfolioId", portfolio.portfolioId().value());
            output.put("valuation", portfolios.valuation(actor, portfolio.portfolioId(), asOf));
        }
        List<String> evidence = List.of(CapabilityJson.evidenceId("ev-portfolio", task.inputHash() + "|" + owner.value()));
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, output), evidence);
    }
}
