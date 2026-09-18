package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.routing.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FundAgentConfigurationTest {
    @Test void missingRouterKeyUsesDeterministicNoopAdvisor(){
        var advisor=new FundAgentConfiguration().semanticRouteAdvisor(new ObjectMapper(),new MockEnvironment(),ObservationRegistry.NOOP);
        assertThat(advisor.advise("隐式复杂问题",new ExecutionModeRouter().features("隐式复杂问题"))).isEmpty();
    }

    @Test void routerKeyBuildsDedicatedQwenAdvisorWithoutReplacingPrimaryModel() throws Exception {
        var environment=new MockEnvironment()
                .withProperty("fund.agent.routing.api-key","test-router-key")
                .withProperty("fund.agent.routing.base-url","https://dashscope.aliyuncs.com/compatible-mode/v1")
                .withProperty("fund.agent.routing.model","qwen3.8-flash");
        RouteAdvisor advisor=new FundAgentConfiguration().semanticRouteAdvisor(new ObjectMapper(),environment,ObservationRegistry.NOOP);
        assertThat(advisor).isInstanceOf(SpringAiRouteAdvisor.class);
        ((SpringAiRouteAdvisor)advisor).close();
    }
}
