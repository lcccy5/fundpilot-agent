package com.jijing.fund.bootstrap;

import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
import com.jijing.fund.analytics.model.DataCoverage;
import com.jijing.fund.analytics.model.DrawdownPeriod;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundComparisonResult;
import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
import com.jijing.fund.application.dto.NavPointResult;
import com.jijing.fund.application.research.RealtimeFundQuoteResult;
import com.jijing.fund.application.research.RealtimeFundQuoteStatus;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.research.RealtimeFundQuoteView;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.model.SourcedValue;
import com.jijing.fund.knowledge.api.KnowledgeSearchResult;
import com.jijing.fund.knowledge.api.KnowledgeSearchUseCase;
import com.jijing.fund.knowledge.domain.DocumentChunk;
import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.RetrievedChunk;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Replaces only external/business data adapters while preserving Agent orchestration and tool execution. */
@Configuration(proxyBeanMethods = false)
@Profile("agent-eval")
public class AgentEvaluationFixtureConfiguration {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    /** Supplies deterministic profile and NAV values labeled by the active fixture. */
    @Bean @Primary
    FundQueryUseCase evaluationFundQueryUseCase() {
        return new FundQueryUseCase() {
            @Override public FundProfileResult getProfile(String code) {
                return new FundProfileResult(code, "评测基金-" + AgentEvaluationFixtureContext.current(), "混合型", "评测基金公司",
                        "评测基金经理", LocalDate.of(2015, 1, 1), "agent-eval-fixture", NOW, NOW, "FRESH");
            }
            @Override public FundNavHistoryResult getNavHistory(String code, LocalDate start, LocalDate end) {
                return new FundNavHistoryResult(code, "agent-eval-fixture", List.of(
                        new NavPointResult(start, new BigDecimal("1.0000"), new BigDecimal("1.1000"), BigDecimal.ZERO),
                        new NavPointResult(end, new BigDecimal("1.0800"), new BigDecimal("1.1800"), new BigDecimal("0.0800"))));
            }
        };
    }

    /** Supplies deterministic metrics while retaining the real metrics tool and evidence conversion. */
    @Bean @Primary
    FundMetricsQueryUseCase evaluationFundMetricsUseCase() {
        return (code, start, end, basis) -> metrics(code, start, end);
    }

    /** Supplies same-window metrics for every requested fund to exercise the real comparison tool. */
    @Bean @Primary
    FundComparisonUseCase evaluationFundComparisonUseCase() {
        return (codes, start, end, basis) -> new FundComparisonResult(start, end, NavBasis.ACCUMULATED_NAV,
                codes.stream().map(code -> metrics(code, start, end)).toList(), Map.of());
    }

    /** Supplies one auditable document chunk for document and mixed Tool/RAG cases. */
    @Bean @Primary
    KnowledgeSearchUseCase evaluationKnowledgeSearchUseCase() {
        return query -> {
            DocumentChunk chunk = new DocumentChunk("fixture-chunk", "fixture-document", "v1",
                    "该基金季报披露行业集中度与市场波动风险，数据仅用于本地评测。", "风险披露", 1, 1, 0, 32,
                    "fixture-v1", "fixture-sha", query.fundCodes(), FundDocumentType.QUARTERLY_REPORT,
                    "本地评测季报-" + AgentEvaluationFixtureContext.current(), LocalDate.of(2026, 6, 30),
                    "agent-eval-fixture", "https://example.invalid/eval-document");
            return new KnowledgeSearchResult("fixture-retrieval", List.of(new RetrievedChunk(chunk, 1D, 1, Set.of("fixture"))), List.of());
        };
    }

    /** Supplies an ETF proxy quote with complete typed lineage for the real realtime tool. */
    @Bean @Primary
    RealtimeFundQuoteUseCase evaluationRealtimeQuoteUseCase() {
        return code -> {
            RealtimeFundQuoteView view = new RealtimeFundQuoteView(code, "510300", "评测ETF", new BigDecimal("4.200"),
                    new BigDecimal("4.100"), new BigDecimal("0.100"), new BigDecimal("0.02439"), NOW,
                    MarketDataKind.UNDERLYING_ETF_PROXY, "场内ETF代理行情，不是场外基金官方净值");
            DataProvenance source = new DataProvenance(new ProviderId("agent-eval"), URI.create("https://example.invalid/quote"),
                    MarketDataKind.UNDERLYING_ETF_PROXY, AgentEvaluationFixtureContext.current(), NOW, NOW, QualityStatus.VERIFIED, List.of());
            return new RealtimeFundQuoteResult(RealtimeFundQuoteStatus.AVAILABLE,
                    new SourcedValue<>(view, new DataLineage(List.of(source), null)), List.of("LOCAL_EVAL_FIXTURE"));
        };
    }

    private static FundMetrics metrics(String code, LocalDate start, LocalDate end) {
        MetricValue value = MetricValue.available(new BigDecimal("0.08000000"));
        return new FundMetrics(new FundCode(code), start, end, start, end, NavBasis.ACCUMULATED_NAV, 120,
                DataCoverage.of(120, 120), value, value, value, MetricValue.available(new BigDecimal("0.12000000")),
                new DrawdownPeriod(start, end, null), MetricValue.available(new BigDecimal("0.65000000")),
                value, value, MetricValue.available(new BigDecimal("-0.02000000")), BigDecimal.ZERO,
                "agent-eval-metrics-v1", AgentEvaluationFixtureContext.current(), NOW);
    }
}
