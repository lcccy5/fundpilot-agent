package com.jijing.fund.bootstrap;

import static org.mockito.Mockito.mock;
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
import org.mockito.stubbing.Answer;
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
     * 查询默认返回空列表，单个测试可以换成抛错或返回运行行。
     */
    @BeforeEach
    void setUp() {
        bind(invocation -> List.of());
    }

    /**
     * 按 SQL 文本决定 {@code queryForList} 的结果，避开 JdbcTemplate 上重载方法的桩歧义。
     */
    private void bind(Answer<Object> queryForList) {
        jdbc = mock(JdbcTemplate.class, invocation -> {
            if ("queryForList".equals(invocation.getMethod().getName())) {
                return queryForList.answer(invocation);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
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
        bind(invocation -> {
            throw new DataRetrievalFailureException("db down");
        });
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
        bind(invocation -> List.of(row));
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
