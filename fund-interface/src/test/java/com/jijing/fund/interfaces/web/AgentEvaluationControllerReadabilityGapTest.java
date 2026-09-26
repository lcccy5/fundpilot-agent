package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.exception.AgentModelUnavailableException;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 本地评测入口在控制器层的请求体和模型失败。令牌校验由安全过滤器负责。
 */
@WebMvcTest(controllers = AgentEvaluationController.class)
@ActiveProfiles("agent-eval")
@Import({AgentEvaluationController.class, RequestIdFilter.class, GlobalExceptionHandler.class,
        FundQueryControllerTest.TestApplication.class})
class AgentEvaluationControllerReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean FundAgentUseCase agent;
    @MockBean AgentRuntimeRepository repository;
    @MockBean JdbcTemplate jdbc;

    /**
     * 请求体不是 JSON 时落入通用异常，返回 500。
     */
    @Test
    void malformedBodyBecomesInternalError() throws Exception {
        mvc.perform(post("/internal/v1/agent/evaluations/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 问题为空不会在控制器被拒绝，编排返回的参数错误仍是 400。
     */
    @Test
    void blankQuestionReachesOrchestration() throws Exception {
        when(agent.chat(any())).thenThrow(new AgentInvalidArgumentException("question required"));
        mvc.perform(post("/internal/v1/agent/evaluations/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"c\",\"promptVersion\":\"p\",\"question\":null,\"fixtureId\":\"f\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_INVALID_ARGUMENT"));
    }

    /**
     * 模型不可用时评测执行返回 503。
     */
    @Test
    void modelUnavailable() throws Exception {
        when(agent.chat(any())).thenThrow(new AgentModelUnavailableException("down", new IOException("timeout")));
        mvc.perform(post("/internal/v1/agent/evaluations/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caseId\":\"c\",\"promptVersion\":\"p\",\"question\":\"问题\",\"fixtureId\":\"f\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }
}
