package com.jijing.fund.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.application.research.RealtimeFundQuoteResult;
import com.jijing.fund.application.research.RealtimeFundQuoteStatus;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.research.RealtimeFundQuoteView;
import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import com.jijing.fund.domain.research.model.SourcedValue;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

class FundRealtimeQuoteToolTest {
    @Test
    void staleProxyQuoteRetainsDataKindAndEvidence() {
        RealtimeFundQuoteUseCase useCase = mock(RealtimeFundQuoteUseCase.class);
        AgentRuntimeRepository audit = mock(AgentRuntimeRepository.class);
        when(useCase.query("000001")).thenReturn(staleResult());
        var tool = new FundRealtimeQuoteTool(useCase, new ToolEvidenceFactory());
        var trace = new AgentExecutionTrace("run-1", audit, new ObjectMapper(), 6, 1, Duration.ofSeconds(1));

        var result = tool.quote(new FundRealtimeQuoteTool.Input("000001"),
                new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace)));

        assertThat(result.status()).isEqualTo(ToolResultStatus.DATA_NOT_READY);
        assertThat(result.data().quoteType()).isEqualTo("UNDERLYING_ETF_PROXY");
        assertThat(result.evidence()).extracting(evidence -> evidence.evidenceType())
                .containsExactly("UNDERLYING_ETF_PROXY", "EXCHANGE_TRADED_QUOTE");
        verify(useCase).query("000001");
        verify(audit).recordToolCall(any());
    }

    private RealtimeFundQuoteResult staleResult() {
        Instant now = Instant.parse("2026-08-27T02:00:00Z");
        var link = new DataProvenance(new ProviderId("eastmoney-fund-api"), URI.create("https://example.com/link"),
                MarketDataKind.UNDERLYING_ETF_PROXY, "link-v1", now, now, QualityStatus.VERIFIED, List.of());
        var quote = new DataProvenance(new ProviderId("tencent-quote"), URI.create("https://example.com/quote"),
                MarketDataKind.EXCHANGE_TRADED_QUOTE, "quote-v1", now, now, QualityStatus.STALE, List.of("QUOTE_STALE"));
        var value = new RealtimeFundQuoteView("000001", "159819", "人工智能ETF", new BigDecimal("1.23"),
                new BigDecimal("1.20"), new BigDecimal("0.03"), new BigDecimal("2.50"), now,
                MarketDataKind.UNDERLYING_ETF_PROXY, "代理指标");
        return new RealtimeFundQuoteResult(RealtimeFundQuoteStatus.STALE,
                new SourcedValue<>(value, new DataLineage(List.of(link, quote), null)), List.of("QUOTE_STALE"));
    }
}
