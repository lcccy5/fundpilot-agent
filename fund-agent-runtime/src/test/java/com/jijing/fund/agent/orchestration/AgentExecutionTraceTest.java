package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentExecutionTraceTest {
    @Test void blocksRepeatedIdenticalToolCall(){
        var trace=new AgentExecutionTrace("run-1",mock(AgentRuntimeRepository.class),new ObjectMapper(),6,1,Duration.ofSeconds(1));
        trace.begin("calculate_fund_metrics",new Input("000001"));
        assertThatThrownBy(()->trace.begin("calculate_fund_metrics",new Input("000001")))
                .isInstanceOf(AgentExecutionLimitException.class).hasMessageContaining("Repeated identical");
    }
    @Test void cancelsToolThatExceedsItsOwnTimeout(){
        var trace=new AgentExecutionTrace("run-2",mock(AgentRuntimeRepository.class),new ObjectMapper(),6,1,Duration.ofMillis(20));
        var invocation=trace.begin("slow_tool",new Input("000001"));
        assertThatThrownBy(()->trace.call(invocation,()->{Thread.sleep(500);return "late";}))
                .isInstanceOf(AgentExecutionLimitException.class).hasMessageContaining("timed out");
    }
    @Test void persistsDeterministicFactCardForSuccessfulObservation(){
        AgentRuntimeRepository repository=mock(AgentRuntimeRepository.class);
        var trace=new AgentExecutionTrace("conversation-1","run-3",repository,new ObjectMapper(),6,1,Duration.ofSeconds(1),Duration.ofHours(24),event->{});
        var invocation=trace.begin("calculate_fund_metrics",new Input("000001"));
        var evidence=new EvidenceReference("ev-1","FUND_METRICS","000001",null,null,"ACCUMULATED_NAV","source","v1","a1",Instant.now());
        trace.success(invocation,evidence,new Observation("000001","12%"));
        var captor=org.mockito.ArgumentCaptor.forClass(AgentFactCard.class);verify(repository).saveFactCard(captor.capture());
        assertThat(captor.getValue().evidenceIds()).containsExactly("ev-1");
        assertThat(captor.getValue().dataJson()).contains("12%");
    }
    record Input(String fundCode){}
    record Observation(String fundCode,String returnRate){}
}
