package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.agent.exception.AgentModelUnavailableException;
import com.jijing.fund.agent.mcp.FundMcpServer;
import com.jijing.fund.agent.notification.NotificationStore;
import com.jijing.fund.agent.report.MonthlyReportLauncher;
import com.jijing.fund.agent.report.ReportJobStore;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 月报、通知和 MCP 清单的匿名访问与下游失败。
 */
@WebMvcTest(controllers = {ReportController.class, NotificationController.class, McpCapabilitiesController.class})
@Import({ReportController.class, NotificationController.class, McpCapabilitiesController.class, RequestIdFilter.class,
        GlobalExceptionHandler.class, ReadabilityGapMvc.class, FundQueryControllerTest.TestApplication.class})
class ReportNotificationMcpReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean ReportJobStore jobs;
    @MockBean MonthlyReportLauncher launcher;
    @MockBean NotificationStore notifications;
    @MockBean FundMcpServer mcp;
    private final AuthenticatedUser user = new AuthenticatedUser(
            new UserId("00000000-0000-0000-0000-000000000005"), Set.of(UserRole.USER), "session-r");
    private final AuthenticatedUser admin = new AuthenticatedUser(
            new UserId("00000000-0000-0000-0000-000000000006"), Set.of(UserRole.ADMIN), "session-admin");

    /**
     * 未登录不能列出月报。
     */
    @Test
    void reportsRequireUser() throws Exception {
        mvc.perform(get("/api/v1/reports"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 对账过程失败时列表返回 500。
     */
    @Test
    void reportReconcileFailure() throws Exception {
        when(jobs.listOwned(user.userId().value())).thenReturn(List.of(
                new ReportJobStore.ReportJobView("job-1", "run-1", user.userId().value(), "RUNNING", null, null)));
        doThrow(new IllegalStateException("reconcile failed")).when(launcher).reconcile(any(), anyString());
        mvc.perform(get("/api/v1/reports").principal(principal(user)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 启动月报时模型不可用返回 503。
     */
    @Test
    void launchMonthlyModelUnavailable() throws Exception {
        when(launcher.launch(anyString(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new AgentModelUnavailableException("model down", new IOException("timeout")));
        mvc.perform(post("/api/v1/reports/monthly").principal(principal(user)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }

    /**
     * 未登录不能读取通知。
     */
    @Test
    void notificationsRequireUser() throws Exception {
        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 通知存储失败返回 500。
     */
    @Test
    void notificationStoreFailure() throws Exception {
        when(notifications.listOwned(user.userId().value())).thenThrow(new IllegalStateException("store down"));
        mvc.perform(get("/api/v1/notifications").principal(principal(user)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    /**
     * 没有登录主体时 MCP 清单返回 401，而不是 403。
     */
    @Test
    void mcpWithoutUserIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/mcp/capabilities"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 角色集合为空时按非管理员拒绝，返回 403。
     */
    @Test
    void mcpNullRolesAreForbidden() throws Exception {
        AuthenticatedUser noRoles = new AuthenticatedUser(
                new UserId("00000000-0000-0000-0000-000000000007"), null, "session-empty");
        mvc.perform(get("/api/v1/mcp/capabilities").principal(principal(noRoles)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    /**
     * 管理员读取清单时服务器自身失败返回 500。
     */
    @Test
    void mcpServerFailure() throws Exception {
        when(mcp.schemaHash()).thenThrow(new IllegalStateException("mcp down"));
        mvc.perform(get("/api/v1/mcp/capabilities").principal(principal(admin)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 构造指定用户的认证主体。
     */
    private UsernamePasswordAuthenticationToken principal(AuthenticatedUser actor) {
        return new UsernamePasswordAuthenticationToken(actor, null, List.of());
    }
}
