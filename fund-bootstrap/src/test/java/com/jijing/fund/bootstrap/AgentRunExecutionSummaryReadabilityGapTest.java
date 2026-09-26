package com.jijing.fund.bootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.interfaces.web.CurrentUserArgumentResolver;
import com.jijing.fund.interfaces.web.GlobalExceptionHandler;
import com.jijing.fund.interfaces.web.RequestIdFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/**
 * 运行摘要的匿名、缺失、数据库失败，以及 AgentOps 不可达时的降级。
 */
class AgentRunExecutionSummaryReadabilityGapTest {
    private static final String USER = "00000000-0000-0000-0000-000000000008";
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    /**
     * 用独立的 MockMvc 装配摘要控制器，避免拉起完整应用和数据源。
     */
    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        AgentRunExecutionSummaryController controller = new AgentRunExecutionSummaryController(
                jdbc, new ObjectMapper(), RestClient.builder(), "http://127.0.0.1:1", "test-token");
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .addFilters(new RequestIdFilter())
                .build();
    }

    /**
     * 没有登录主体时返回 401。
     */
    @Test
    void summaryRequiresUser() throws Exception {
        mvc.perform(get("/api/v1/agent/runs/run-1/queryExecutionSummary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 属主查询没有行时返回 404。
     */
    @Test
    void missingRunIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/agent/runs/run-1/queryExecutionSummary").principal(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
    }

    /**
     * 读取运行失败时返回 500，响应不包含数据库异常文本。
     */
    @Test
    void databaseFailureIsInternalError() throws Exception {
        when(jdbc.queryForList(anyString(), any(), any())).thenThrow(new DataRetrievalFailureException("db down"));
        mvc.perform(get("/api/v1/agent/runs/run-1/queryExecutionSummary").principal(principal()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 运行存在但记账服务拒绝连接时，摘要仍成功，并把 AgentOps 标成不可用。
     */
    @Test
    void agentOpsOutageDegrades() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run_id", "run-1");
        row.put("status", "SUCCEEDED");
        when(jdbc.queryForList(contains("agent_run"), any(), any())).thenReturn(List.of(row));
        when(jdbc.queryForList(contains("agent_tool_call"), any())).thenReturn(List.of());
        when(jdbc.queryForList(contains("agent_fact_card"), any())).thenReturn(List.of());
        mvc.perform(get("/api/v1/agent/runs/run-1/queryExecutionSummary").principal(principal()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.agentOps.available").value(false))
                .andExpect(jsonPath("$.data.agentOps.status").value("UNAVAILABLE"));
    }

    /**
     * 构造摘要所属用户。
     */
    private UsernamePasswordAuthenticationToken principal() {
        AuthenticatedUser user = new AuthenticatedUser(new UserId(USER), Set.of(UserRole.USER), "session-s");
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }
}
