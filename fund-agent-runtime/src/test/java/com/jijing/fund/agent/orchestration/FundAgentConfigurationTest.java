package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.routing.*;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 确认语义路由缺少密钥时使用空顾问，有密钥时才创建专用预检客户端。 空顾问使未知语义请求失败开放，而不是启动会失败的模型调用。 */
class FundAgentConfigurationTest {
  /** 没有路由密钥时建议始终为空。 路由器因此不会把配置缺失升级成计划执行。 */
  @Test
  void missingRouterKeyUsesDeterministicNoopAdvisor() {
    var advisor =
        new FundAgentConfiguration()
            .semanticRouteAdvisor(
                new ObjectMapper(), new MockEnvironment(), ObservationRegistry.NOOP);
    assertThat(advisor.advise("隐式复杂问题", new ExecutionModeRouter().features("隐式复杂问题"))).isEmpty();
  }

  /** 配置了密钥时创建可关闭的语义顾问。 主聊天模型不被这个预检客户端替换。 */
  @Test
  void routerKeyBuildsDedicatedQwenAdvisorWithoutReplacingPrimaryModel() throws Exception {
    var environment =
        new MockEnvironment()
            .withProperty("fund.agent.routing.api-key", "test-router-key")
            .withProperty(
                "fund.agent.routing.base-url", "https://dashscope.aliyuncs.com/compatible-mode/v1")
            .withProperty("fund.agent.routing.model", "qwen3.8-flash");
    RouteAdvisor advisor =
        new FundAgentConfiguration()
            .semanticRouteAdvisor(new ObjectMapper(), environment, ObservationRegistry.NOOP);
    assertThat(advisor).isInstanceOf(SpringAiRouteAdvisor.class);
    ((SpringAiRouteAdvisor) advisor).close();
  }
}
