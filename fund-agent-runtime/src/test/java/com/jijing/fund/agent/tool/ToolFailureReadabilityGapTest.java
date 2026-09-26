package com.jijing.fund.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.portfolio.PortfolioException;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.research.RealtimeFundQuoteResult;
import com.jijing.fund.application.research.RealtimeFundQuoteStatus;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.PortfolioStatus;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class ToolFailureReadabilityGapTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final LocalDate start = LocalDate.of(2026, 2, 1);
    private final LocalDate end = LocalDate.of(2026, 1, 1);

    @Test
    void metricsRejectsInvertedDatesAndUseCaseFailuresWithoutFabricatingZero() {
        FundMetricsQueryUseCase useCase = mock(FundMetricsQueryUseCase.class);
        var tool = new FundMetricsTool(useCase, validator);
        var inverted = tool.calculate(new FundMetricsTool.MetricsInput("000001", start, end, "ACCUMULATED_NAV"), traced());
        assertThat(inverted.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(inverted.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(inverted.data()).isNull();
        assertThat(inverted.evidence()).isEmpty();

        var invalidCode = tool.calculate(new FundMetricsTool.MetricsInput("12", start, start, null), traced());
        assertThat(invalidCode.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(invalidCode.errorCode()).isEqualTo("INVALID_ARGUMENT");

        when(useCase.calculate("000001", start, start, null)).thenThrow(new IllegalStateException("nav hole"));
        var unavailable = tool.calculate(new FundMetricsTool.MetricsInput("000001", start, start, null), traced());
        assertThat(unavailable.status()).isEqualTo(ToolResultStatus.DATA_NOT_READY);
        assertThat(unavailable.errorCode()).isEqualTo("METRICS_NOT_READY");
        assertThat(unavailable.safeErrorMessage()).contains("nav hole");
    }

    @Test
    void profileTurnsIllegalArgumentIntoDataNotReadyAndConstraintFailuresStayCorrectable() {
        FundQueryUseCase useCase = mock(FundQueryUseCase.class);
        var tool = new FundProfileTool(useCase, validator);
        var invalid = tool.getProfile(new FundProfileTool.ProfileInput("abc"), traced());
        assertThat(invalid.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(invalid.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(invalid.safeErrorMessage()).contains("fundCode");

        when(useCase.getProfile("000001")).thenThrow(new IllegalArgumentException("unknown fund"));
        var failed = tool.getProfile(new FundProfileTool.ProfileInput("000001"), traced());
        assertThat(failed.status()).isEqualTo(ToolResultStatus.DATA_NOT_READY);
        assertThat(failed.errorCode()).isEqualTo("FUND_PROFILE_FAILED");
        assertThat(failed.safeErrorMessage()).isEqualTo("unknown fund");
    }

    @Test
    void navAndComparisonReturnCorrectableOrNotReadyWithoutEvidence() {
        FundQueryUseCase navs = mock(FundQueryUseCase.class);
        var nav = new FundNavTool(navs, validator);
        var inverted = nav.getNav(new FundNavTool.NavInput("000001", start, end), traced());
        assertThat(inverted.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(inverted.evidence()).isEmpty();
        when(navs.getNavHistory("000001", start, start)).thenThrow(new IllegalStateException("source down"));
        var navFailed = nav.getNav(new FundNavTool.NavInput("000001", start, start), traced());
        assertThat(navFailed.errorCode()).isEqualTo("NAV_QUERY_FAILED");

        FundComparisonUseCase comparisons = mock(FundComparisonUseCase.class);
        var comparison = new FundComparisonTool(comparisons, validator);
        var tooFew = comparison.compare(new FundComparisonTool.ComparisonInput(List.of("000001"), start, start, null), traced());
        assertThat(tooFew.errorCode()).isEqualTo("INVALID_ARGUMENT");
        when(comparisons.compare(List.of("000001", "000002"), start, start, null)).thenThrow(new IllegalStateException("overlap missing"));
        var notReady = comparison.compare(new FundComparisonTool.ComparisonInput(List.of("000001", "000002"), start, start, null), traced());
        assertThat(notReady.status()).isEqualTo(ToolResultStatus.DATA_NOT_READY);
        assertThat(notReady.errorCode()).isEqualTo("COMPARISON_NOT_READY");
    }

    @Test
    void documentSearchFailsClosedWhenQueryIsInvalidOrSourceThrows() {
        KnowledgeSearchUseCase useCase = mock(KnowledgeSearchUseCase.class);
        var tool = new FundDocumentSearchTool(useCase, validator);
        var invalid = tool.search(new FundDocumentSearchTool.SearchInput(List.of(), "波动", null, null, null, null), traced());
        assertThat(invalid.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(invalid.errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(invalid.evidence()).isEmpty();

        when(useCase.search(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException("index down"));
        var failed = tool.search(new FundDocumentSearchTool.SearchInput(List.of("000001"), "波动", null, null, null, null), traced());
        assertThat(failed.errorCode()).isEqualTo("DOCUMENT_SEARCH_FAILED");
        assertThat(failed.data()).isNull();
    }

    @Test
    void realtimeQuoteSeparatesMissingProxyUnsupportedAndSourceFailure() {
        RealtimeFundQuoteUseCase useCase = mock(RealtimeFundQuoteUseCase.class);
        var tool = new FundRealtimeQuoteTool(useCase, new ToolEvidenceFactory());
        when(useCase.query("000001")).thenReturn(RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.NO_EXCHANGE_PROXY, "none"));
        var noProxy = tool.quote(new FundRealtimeQuoteTool.Input("000001"), traced());
        assertThat(noProxy.errorCode()).isEqualTo("NO_EXCHANGE_PROXY");
        assertThat(noProxy.evidence()).isEmpty();

        when(useCase.query("000002")).thenReturn(RealtimeFundQuoteResult.unavailable(RealtimeFundQuoteStatus.DATA_NOT_READY, "late"));
        var notReady = tool.quote(new FundRealtimeQuoteTool.Input("000002"), traced());
        assertThat(notReady.errorCode()).isEqualTo("REALTIME_QUOTE_UNAVAILABLE");

        when(useCase.query("000003")).thenThrow(new IllegalArgumentException("not a linked fund"));
        var unsupported = tool.quote(new FundRealtimeQuoteTool.Input("000003"), traced());
        assertThat(unsupported.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(unsupported.errorCode()).isEqualTo("REALTIME_QUOTE_UNSUPPORTED");
        assertThat(unsupported.safeErrorMessage()).isEqualTo("not a linked fund");

        when(useCase.query("000004")).thenThrow(new IllegalStateException("timeout"));
        var failed = tool.quote(new FundRealtimeQuoteTool.Input("000004"), traced());
        assertThat(failed.errorCode()).isEqualTo("REALTIME_QUOTE_FAILED");
        assertThat(failed.safeErrorMessage()).isEqualTo("实时行情数据源暂不可用");

        RealtimeFundQuoteResult inconsistent = mock(RealtimeFundQuoteResult.class);
        when(inconsistent.quote()).thenReturn(null);
        when(inconsistent.status()).thenReturn(RealtimeFundQuoteStatus.AVAILABLE);
        when(useCase.query("000005")).thenReturn(inconsistent);
        var defensive = tool.quote(new FundRealtimeQuoteTool.Input("000005"), traced());
        assertThat(defensive.errorCode()).isEqualTo("REALTIME_QUOTE_FAILED");
    }

    @Test
    void personalToolsFailWhenIdentityOrPortfolioIsMissing() {
        WatchlistUseCase watchlists = mock(WatchlistUseCase.class);
        PortfolioUseCase portfolios = mock(PortfolioUseCase.class);
        var tool = new PersonalFundTool(watchlists, portfolios);
        var missingUser = tool.watchlist(traced());
        assertThat(missingUser.errorCode()).isEqualTo("PERSONAL_DATA_UNAVAILABLE");
        assertThat(missingUser.safeErrorMessage()).contains("authenticated user context is required");
        assertThat(missingUser.evidence()).isEmpty();

        assertThatThrownBy(() -> tool.watchlist(new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent execution trace is required");

        when(portfolios.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var noPortfolio = tool.portfolio(new PersonalFundTool.PortfolioInput(null, null), tracedUser());
        assertThat(noPortfolio.safeErrorMessage()).isEqualTo("no portfolio available");
        var missingReturn = tool.returns(new PersonalFundTool.PortfolioInput("", null), tracedUser());
        assertThat(missingReturn.errorCode()).isEqualTo("PERSONAL_DATA_UNAVAILABLE");
        var missingRisk = tool.risk(new PersonalFundTool.PortfolioInput(null, start), tracedUser());
        assertThat(missingRisk.safeErrorMessage()).isEqualTo("no portfolio available");

        String otherId = UUID.randomUUID().toString();
        when(portfolios.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(portfolio(otherId)));
        var notFound = tool.portfolio(new PersonalFundTool.PortfolioInput(UUID.randomUUID().toString(), null), tracedUser());
        assertThat(notFound.safeErrorMessage()).isEqualTo("portfolio not found");

        when(watchlists.list(org.mockito.ArgumentMatchers.any())).thenThrow(new PortfolioException("watchlist down"));
        var compare = tool.compare(tracedUser());
        assertThat(compare.errorCode()).isEqualTo("PERSONAL_DATA_UNAVAILABLE");
        assertThat(compare.safeErrorMessage()).isEqualTo("watchlist down");
    }

    @Test
    void supportAndEvidenceRejectMissingContextAndBlankLineage() {
        assertThatThrownBy(() -> FundToolSupport.trace(new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent execution trace is required");
        assertThatThrownBy(() -> FundToolSupport.user(traced()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("authenticated user context is required");
        assertThat(FundToolSupport.safeMessage(new IllegalStateException())).isEqualTo("IllegalStateException");
        assertThat(FundToolSupport.safeMessage(new IllegalStateException("x".repeat(301)))).hasSize(300);
        assertThatThrownBy(() -> FundToolSupport.validate(validator, new FundProfileTool.ProfileInput("nope")))
                .isInstanceOf(ConstraintViolationException.class);
        var violations = (ConstraintViolationException) org.assertj.core.api.Assertions.catchThrowable(
                () -> FundToolSupport.validate(validator, new FundProfileTool.ProfileInput("nope")));
        assertThat(FundToolSupport.validationMessage(violations)).contains("fundCode");

        var factory = new ToolEvidenceFactory();
        assertThatThrownBy(() -> factory.create(" ", "000001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("evidenceScope is required");
        assertThatThrownBy(() -> factory.create("fund_realtime_quote", "000001", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lineage is required");

        var envelope = new FundToolEnvelope<>("tool", "v", ToolResultStatus.SUCCESS, "data", null, null, null, null);
        assertThat(envelope.evidence()).isEmpty();
        assertThat(envelope.warnings()).isEmpty();
        assertThat(FundToolEnvelope.success("tool", "data", null, List.of()).evidence()).isEmpty();
    }

    @Test
    void routerAndSectorFailBeforeCallingExternalDataWhenContextIsAbsent() {
        var router = new FundToolRouter(mock(FundProfileTool.class), mock(FundNavTool.class), mock(FundMetricsTool.class),
                mock(FundComparisonTool.class), mock(FundDocumentSearchTool.class));
        assertThatThrownBy(() -> router.toolsFor(null)).isInstanceOf(NullPointerException.class);

        var sector = new SectorOutlookTool();
        assertThatThrownBy(() -> sector.analyze(new SectorOutlookTool.Input("机器人", 30), new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent execution trace is required");
    }

    private ToolContext traced() {
        AgentRuntimeRepository audit = mock(AgentRuntimeRepository.class);
        var trace = new AgentExecutionTrace("run-1", audit, new ObjectMapper(), 6, 1, Duration.ofSeconds(2));
        return new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace));
    }

    private ToolContext tracedUser() {
        AgentRuntimeRepository audit = mock(AgentRuntimeRepository.class);
        var trace = new AgentExecutionTrace("run-1", audit, new ObjectMapper(), 6, 1, Duration.ofSeconds(2));
        var user = new AuthenticatedUser(new UserId(UUID.randomUUID().toString()), Set.of(UserRole.USER), "session");
        Map<String, Object> values = new HashMap<>();
        values.put(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace);
        values.put(AgentExecutionTrace.USER_CONTEXT_KEY, user);
        return new ToolContext(values);
    }

    private UserPortfolio portfolio(String id) {
        Instant now = Instant.parse("2026-09-26T00:00:00Z");
        return new UserPortfolio(new PortfolioId(id), new UserId(UUID.randomUUID().toString()), "默认组合", "CNY",
                PortfolioStatus.ACTIVE, 1, now, now);
    }
}
