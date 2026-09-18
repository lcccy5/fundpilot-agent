package com.jijing.fund.agent.routing;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.*;

class SpringAiRouteAdvisorTest {
    @Test void parsesBoundedStructuredAdvice(){
        ChatModel model=prompt->new ChatResponse(List.of(new Generation(new AssistantMessage("""
                {"goals":["核验异常","分析原因"],"requiredCapabilities":["market_quote","document_search"],
                 "hasDependencies":true,"crossSourceVerificationRequired":true,
                 "iterativeResearchRequired":true,"estimatedStages":3,
                 "rationale":"requires cross-source verification"}
                """))),ChatResponseMetadata.builder().model("router-test").build());
        try(var advisor=new SpringAiRouteAdvisor(model,new ObjectMapper(),Duration.ofSeconds(1))){
            var advice=advisor.advise("这只基金最近不太对劲",new ExecutionModeRouter().features("这只基金最近不太对劲"));
            assertThat(advice).isPresent();
            assertThat(advice.orElseThrow().crossSourceVerificationRequired()).isTrue();
            assertThat(advice.orElseThrow().estimatedStages()).isEqualTo(3);
        }
    }

    @Test void malformedProviderResponseFailsOpen(){
        ChatModel model=prompt->new ChatResponse(List.of(new Generation(new AssistantMessage("not-json"))));
        try(var advisor=new SpringAiRouteAdvisor(model,new ObjectMapper(),Duration.ofSeconds(1))){
            assertThat(advisor.advise("查询基金",new ExecutionModeRouter().features("查询基金"))).isEmpty();
        }
    }
}
