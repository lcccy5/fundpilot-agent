package com.jijing.fund.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.web.client.RestClient;

class CatalystResearchFailureReadabilityGapTest {
    @Test
    void rejectsBlankSubjectAndMalformedCodeWithoutCallingHoldings() {
        var tool = new FundCatalystResearchTool();
        var blank = tool.research(new FundCatalystResearchTool.Input("  ", null, null, null), traced(6, 1));
        assertThat(blank.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(blank.errorCode()).isEqualTo("CATALYST_RESEARCH_UNSUPPORTED");
        assertThat(blank.safeErrorMessage()).contains("基金代码或具体行业主题");
        assertThat(blank.evidence()).isEmpty();

        var badCode = tool.research(new FundCatalystResearchTool.Input(null, "12", 10, 45), traced(6, 1));
        assertThat(badCode.errorCode()).isEqualTo("CATALYST_RESEARCH_UNSUPPORTED");
        assertThat(badCode.safeErrorMessage()).contains("6位数字");
    }

    @Test
    void rethrowsExecutionLimitAndFailsClosedWhenDailyBasketIsUnavailable() {
        var tool = new FundCatalystResearchTool();
        assertThatThrownBy(() -> tool.research(new FundCatalystResearchTool.Input(null, "159001", null, null), traced(6, 0)))
                .isInstanceOf(AgentExecutionLimitException.class);
        assertThatThrownBy(() -> tool.research(new FundCatalystResearchTool.Input(null, "159001", null, null), new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Agent execution trace is required");

        RestClient broken = mock(RestClient.class);
        when(broken.get()).thenThrow(new IllegalStateException("source down"));
        var clock = Clock.fixed(Instant.parse("2026-09-26T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
        var injected = new FundCatalystResearchTool(new ObjectMapper(), clock, broken, broken, broken, broken, broken, broken, broken, broken);

        var noBasket = injected.research(new FundCatalystResearchTool.Input(null, "000001", 10, 45), traced(6, 1));
        assertThat(noBasket.status()).isEqualTo(ToolResultStatus.USER_CORRECTABLE);
        assertThat(noBasket.safeErrorMessage()).contains("日频 ETF 申赎篮子");

        var szseDown = injected.research(new FundCatalystResearchTool.Input(null, "159001", 10, 45), traced(6, 1));
        assertThat(szseDown.status()).isEqualTo(ToolResultStatus.DATA_NOT_READY);
        assertThat(szseDown.errorCode()).isEqualTo("CATALYST_DATA_UNAVAILABLE");
        assertThat(szseDown.safeErrorMessage()).isEqualTo("持仓或公告数据源暂不可用，请稍后重试");

        var sseDown = injected.research(new FundCatalystResearchTool.Input(null, "510050", 10, 45), traced(6, 1));
        assertThat(sseDown.errorCode()).isEqualTo("CATALYST_DATA_UNAVAILABLE");
        assertThat(sseDown.data()).isNull();
    }

    @Test
    void emptyPcfTextProducesNoConstituents() {
        assertThat(FundCatalystResearchTool.parsePcf(null)).isEmpty();
        assertThat(FundCatalystResearchTool.parsePcf("没有组合段")).isEmpty();
    }

    private ToolContext traced(int maxCalls, int maxRepeats) {
        AgentRuntimeRepository audit = mock(AgentRuntimeRepository.class);
        var trace = new AgentExecutionTrace("run-1", audit, new ObjectMapper(), maxCalls, maxRepeats, Duration.ofSeconds(2));
        return new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace));
    }
}
