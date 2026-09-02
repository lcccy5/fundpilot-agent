package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.tool.FundToolRouter;
import java.time.*;
import java.util.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SpringAiFundAgentServiceTest {
    @Test void completesConversationWithFakeModelAndAudit(){
        AgentRuntimeRepository repository=mock(AgentRuntimeRepository.class);when(repository.conversationExists("00000000-0000-0000-0000-000000000001")).thenReturn(true);when(repository.startRun(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn("run-1");
        FundToolRouter router=mock(FundToolRouter.class);when(router.toolsFor(anyString())).thenReturn(new Object[]{});
        ChatMemory memory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        FundAgentProperties properties=FundAgentProperties.of(true,"fund-agent-v1","fund-tools-v1",6,1,5,Duration.ofSeconds(5),Duration.ofSeconds(1),2000,20);
        ChatModel model=new ChatModel(){@Override public ChatResponse call(Prompt prompt){return new ChatResponse(List.of(new Generation(new AssistantMessage("我可以解释基金历史数据，但不承诺未来收益。"))),ChatResponseMetadata.builder().model("fake-model").build());}};
        SimpleMeterRegistry meters=new SimpleMeterRegistry();
        String promptText="你是基金助手，不得承诺收益。";
        var prompt=new FundAgentPrompt("fund-agent-v1",promptText,AgentExecutionTrace.sha256(promptText));
        try(var service=new SpringAiFundAgentService(model,memory,repository,properties,router,new ObjectMapper().findAndRegisterModules(),Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC),new FundAgentSafetyPolicy(),new FundAgentCitationPolicy(),meters,prompt,new AgentModelDescriptor("test-provider","configured-test-model"))){
            FundAgentResponse result=service.chat(new FundAgentRequest("00000000-0000-0000-0000-000000000001","你能做什么？","req-1"));
            assertThat(result.answer()).contains("不承诺");assertThat(result.modelName()).isEqualTo("fake-model");assertThat(result.evidence()).isEmpty();
            verify(repository).completeRun(eq("run-1"),eq(1),eq(0),any(),anyLong(),any());
            assertThat(meters.counter("fund.agent.runs","status","success").count()).isEqualTo(1);
        }
    }
    @Test void emitsIncrementalDeltasAndCompletesStreamAudit(){
        String conversation="00000000-0000-0000-0000-000000000002";AgentRuntimeRepository repository=mock(AgentRuntimeRepository.class);when(repository.conversationExists(conversation)).thenReturn(true);when(repository.startRun(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn("run-stream");FundToolRouter router=mock(FundToolRouter.class);when(router.toolsFor(anyString())).thenReturn(new Object[]{});ChatMemory memory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();FundAgentProperties properties=FundAgentProperties.of(true,"fund-agent-v1","fund-tools-v1",6,1,5,Duration.ofSeconds(5),Duration.ofSeconds(1),2000,20);
        ChatModel model=new ChatModel(){@Override public ChatResponse call(Prompt p){throw new UnsupportedOperationException();}@Override public Flux<ChatResponse>stream(Prompt p){return Flux.just(response("这是"),response("增量回答。"));}private ChatResponse response(String text){return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),ChatResponseMetadata.builder().model("fake-stream-model").build());}};
        String promptText="你是基金助手。";var prompt=new FundAgentPrompt("fund-agent-v1",promptText,AgentExecutionTrace.sha256(promptText));
        try(var service=new SpringAiFundAgentService(model,memory,repository,properties,router,new ObjectMapper().findAndRegisterModules(),Clock.systemUTC(),new FundAgentSafetyPolicy(),new FundAgentCitationPolicy(),new SimpleMeterRegistry(),prompt,new AgentModelDescriptor("test","fake-stream-model"))){var events=service.stream(new FundAgentRequest(conversation,"你好","req-stream")).collectList().block();assertThat(events).extracting(FundAgentEvent::type).containsExactly("run.started","answer.delta","evidence.verifying","answer.completed");assertThat(events.get(1).data()).isEqualTo("这是增量回答。");verify(repository).completeRun(eq("run-stream"),eq(1),eq(0),any(),anyLong(),any());}
    }
    @Test void reusesUnexpiredFactCardWithItsOriginalEvidence(){
        String conversation="00000000-0000-0000-0000-000000000003";Instant now=Instant.parse("2026-08-23T00:00:00Z");
        AgentRuntimeRepository repository=mock(AgentRuntimeRepository.class);when(repository.conversationExists(conversation)).thenReturn(true);when(repository.startRun(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenReturn("run-fact");
        var evidence=new EvidenceReference("ev-old","FUND_METRICS","000001",LocalDate.of(2026,1,1),LocalDate.of(2026,3,31),"ACCUMULATED_NAV","source","v1","a1",now);
        when(repository.findActiveFactCards(eq(conversation),any(),anyInt())).thenReturn(List.of(new AgentFactCard("card-1",conversation,"old-run","calculate_fund_metrics","000001",List.of(evidence),"{\"return\":\"12%\"}",now.minusSeconds(60),now.plusSeconds(3600))));
        FundToolRouter router=mock(FundToolRouter.class);when(router.toolsFor(anyString())).thenReturn(new Object[]{});ChatMemory memory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        FundAgentProperties properties=FundAgentProperties.of(true,"fund-agent-v1","fund-tools-v1",6,1,5,Duration.ofSeconds(5),Duration.ofSeconds(1),2000,20);
        ChatModel model=p->new ChatResponse(List.of(new Generation(new AssistantMessage("该区间收益为12% ev-old。"))),ChatResponseMetadata.builder().model("fake-model").build());
        var prompt=new FundAgentPrompt("fund-agent-v1","你是基金助手。",AgentExecutionTrace.sha256("你是基金助手。"));
        try(var service=new SpringAiFundAgentService(model,memory,repository,properties,router,new ObjectMapper().findAndRegisterModules(),Clock.fixed(now,ZoneOffset.UTC),new FundAgentSafetyPolicy(),new FundAgentCitationPolicy(),new SimpleMeterRegistry(),prompt,new AgentModelDescriptor("test","fake"))){
            FundAgentResponse response=service.chat(new FundAgentRequest(conversation,"刚才的收益是多少？","req-fact"));
            assertThat(response.answer()).contains("ev-old");assertThat(response.evidence()).contains(evidence);verify(repository).recordFactCardUsage("run-fact",List.of("card-1"),now);
        }
    }
    @Test void chatDoesNotStartMultiAgentForComplexPlan(){
        String conversation="00000000-0000-0000-0000-000000000004";
        AgentRuntimeRepository repository=mock(AgentRuntimeRepository.class);when(repository.conversationExists(eq(conversation),any())).thenReturn(true);
        FundToolRouter router=mock(FundToolRouter.class);when(router.toolsFor(anyString())).thenReturn(new Object[]{});
        ChatMemory memory=MessageWindowChatMemory.builder().chatMemoryRepository(new InMemoryChatMemoryRepository()).maxMessages(20).build();
        FundAgentProperties properties=FundAgentProperties.of(true,"fund-agent-v1","fund-tools-v1",6,1,5,Duration.ofSeconds(5),Duration.ofSeconds(1),2000,20);
        ChatModel model=new ChatModel(){@Override public ChatResponse call(Prompt p){throw new AssertionError("model must not be called for plan-and-execute chat");}};
        AgentRunUseCase runs=mock(AgentRunUseCase.class);
        when(runs.submit(any())).thenReturn(new AgentRunView("plan-run",conversation,"user-a","PLAN_RUNNING","PLAN_AND_EXECUTE","MULTI_GOAL_OR_REPORT","plan-1",1));
        var actor=new com.jijing.fund.domain.identity.AuthenticatedUser(new com.jijing.fund.domain.identity.UserId("00000000-0000-0000-0000-000000000001"),Set.of(com.jijing.fund.domain.identity.UserRole.USER),"s");
        var prompt=new FundAgentPrompt("fund-agent-v1","你是基金助手。",AgentExecutionTrace.sha256("你是基金助手。"));
        try(var service=new SpringAiFundAgentService(model,memory,repository,properties,router,new ObjectMapper().findAndRegisterModules(),Clock.systemUTC(),new FundAgentSafetyPolicy(),new FundAgentCitationPolicy(),new SimpleMeterRegistry(),prompt,new AgentModelDescriptor("test","fake"),new com.jijing.fund.agent.routing.ExecutionModeRouter(),runs)){
            FundAgentResponse response=service.chat(new FundAgentRequest(conversation,"比较 000001 110022 161725 并结合我的组合生成报告","req-plan",actor));
            assertThat(response.runId()).isEqualTo("plan-run");
            assertThat(response.answer()).contains("不会启动多 Agent");
            verify(runs).submit(any());
            verify(repository,never()).startRun(anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),anyString(),any());
        }
    }
}
