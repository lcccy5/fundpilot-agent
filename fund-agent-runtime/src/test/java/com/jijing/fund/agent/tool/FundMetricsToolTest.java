package com.jijing.fund.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.analytics.model.*;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.domain.model.FundCode;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FundMetricsToolTest {
    @Test void delegatesToUseCaseAndReturnsVersionedEvidence(){
        FundMetricsQueryUseCase useCase=mock(FundMetricsQueryUseCase.class);AgentRuntimeRepository audit=mock(AgentRuntimeRepository.class);
        LocalDate start=LocalDate.of(2026,1,2),end=LocalDate.of(2026,1,8);when(useCase.calculate("000001",start,end,"ACCUMULATED_NAV")).thenReturn(metrics(start,end));
        var tool=new FundMetricsTool(useCase,Validation.buildDefaultValidatorFactory().getValidator());
        var trace=new AgentExecutionTrace("run-1",audit,new ObjectMapper().findAndRegisterModules(),6,1,Duration.ofSeconds(1));
        var result=tool.calculate(new FundMetricsTool.MetricsInput("000001",start,end,"ACCUMULATED_NAV"),new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY,trace)));
        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);assertThat(result.evidence()).hasSize(1);
        assertThat(result.evidence().getFirst().dataVersion()).isEqualTo("3");assertThat(result.evidence().getFirst().algorithmVersion()).isEqualTo("fund-metrics-v1");
        verify(useCase).calculate("000001",start,end,"ACCUMULATED_NAV");verify(audit).recordToolCall(any());
    }
    private FundMetrics metrics(LocalDate start,LocalDate end){MetricValue value=MetricValue.available(new BigDecimal("0.01000000"));return new FundMetrics(new FundCode("000001"),start,end,start,end,NavBasis.ACCUMULATED_NAV,5,DataCoverage.of(5,5),value,MetricValue.unavailable("PERIOD_TOO_SHORT"),value,value,new DrawdownPeriod(start,end,null),value,value,value,value,BigDecimal.ZERO,"fund-metrics-v1","3",Instant.parse("2026-01-08T08:00:00Z"));}
}
