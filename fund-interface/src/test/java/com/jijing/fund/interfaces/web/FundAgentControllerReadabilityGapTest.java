package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.exception.AgentDisabledException;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.exception.AgentModelUnavailableException;
import com.jijing.fund.agent.exception.AgentPolicyViolationException;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.planning.PlanValidationException;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Agent 对话和运行接口尚未覆盖的匿名、参数、缺失、约束和模型失败。
 */
@WebMvcTest(controllers = FundAgentController.class)
@Import({FundAgentController.class, RequestIdFilter.class, GlobalExceptionHandler.class, ReadabilityGapMvc.class,
        FundQueryControllerTest.TestApplication.class})
class FundAgentControllerReadabilityGapTest {
    private static final String CHAT = "{\"conversationId\":\"c1\",\"message\":\"查询 000001\"}";
    @Autowired MockMvc mvc;
    @MockBean FundAgentUseCase chat;
    @MockBean AgentRunUseCase runs;
    private final AuthenticatedUser user = new AuthenticatedUser(
            new UserId("00000000-0000-0000-0000-000000000004"), Set.of(UserRole.USER), "session-a");

    /**
     * 同步问答没有登录主体时返回 401。
     */
    @Test
    void chatRequiresUser() throws Exception {
        mvc.perform(post("/api/v1/agent/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 消息为空白时返回 400。
     */
    @Test
    void chatRejectsBlankMessage() throws Exception {
        mvc.perform(post("/api/v1/agent/chat").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\",\"message\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * Agent 未启用时同步问答返回 503。
     */
    @Test
    void chatDisabled() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentDisabledException());
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_DISABLED"));
    }

    /**
     * 模型不可用时同步问答返回 503。
     */
    @Test
    void chatModelUnavailable() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentModelUnavailableException("timeout", new IOException("timeout")));
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
    }

    /**
     * 用例拒绝的对话参数返回 400。
     */
    @Test
    void chatInvalidArgument() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentInvalidArgumentException("conversation missing"));
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_INVALID_ARGUMENT"));
    }

    /**
     * 超出执行额度返回 422。
     */
    @Test
    void chatHitsExecutionLimit() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentExecutionLimitException("too many rounds"));
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_EXECUTION_LIMIT"));
    }

    /**
     * 策略违规返回 422。
     */
    @Test
    void chatPolicyViolation() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentPolicyViolationException("side effect blocked"));
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_POLICY_VIOLATION"));
    }

    /**
     * 证据约束失败返回 422。
     */
    @Test
    void chatEvidenceViolation() throws Exception {
        when(chat.chat(any())).thenThrow(new AgentEvidenceViolationException("no evidence"));
        mvc.perform(post("/api/v1/agent/chat").principal(principal()).contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_EVIDENCE_VIOLATION"));
    }

    /**
     * 流式订阅建立前模型关闭时返回 503。
     */
    @Test
    void streamDisabledBeforeSubscription() throws Exception {
        when(chat.stream(any())).thenThrow(new AgentDisabledException());
        mvc.perform(post("/api/v1/agent/chat/stream").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AGENT_DISABLED"));
    }

    /**
     * 计划不合法时提交运行返回 400。
     */
    @Test
    void submitPlanInvalid() throws Exception {
        when(runs.submit(any())).thenThrow(new PlanValidationException("goal is empty"));
        mvc.perform(post("/api/v1/agent/runs").principal(principal())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"比较基金\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_INVALID"));
    }

    /**
     * 计划不存在或不属于当前用户时返回 404。
     */
    @Test
    void planMissing() throws Exception {
        when(runs.plan("run-1", user.userId().value())).thenThrow(new AgentRunNotFoundException("missing"));
        mvc.perform(get("/api/v1/agent/runs/run-1/plan").principal(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
    }

    /**
     * 报告写作任务尚未成功时返回 500，而不是 409。
     */
    @Test
    void reportNotReadyIsInternalError() throws Exception {
        when(runs.plan("run-1", user.userId().value()))
                .thenReturn(new AgentPlanView("plan-1", "run-1", user.userId().value(), "RUNNING", "goal", 1, List.of()));
        mvc.perform(get("/api/v1/agent/runs/run-1/report").principal(principal()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 取消不存在的运行返回 404。
     */
    @Test
    void cancelMissingRun() throws Exception {
        org.mockito.Mockito.doThrow(new AgentRunNotFoundException("missing"))
                .when(runs).cancel("run-1", user.userId().value());
        mvc.perform(post("/api/v1/agent/runs/run-1/cancel").principal(principal()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
    }

    /**
     * 无法解析的 Last-Event-ID 从 0 重新读取，不返回 400。
     */
    @Test
    void malformedLastEventIdStartsAtZero() throws Exception {
        when(runs.events("run-1", user.userId().value(), 0L)).thenReturn(List.of());
        mvc.perform(get("/api/v1/agent/runs/run-1/events").principal(principal()).header("Last-Event-ID", "not-a-number"))
                .andExpect(status().isOk());
        verify(runs).events("run-1", user.userId().value(), 0L);
    }

    /**
     * 审批参数不合法返回 400。
     */
    @Test
    void approveInvalidArgument() throws Exception {
        org.mockito.Mockito.doThrow(new AgentInvalidArgumentException("bad parameters"))
                .when(runs).approve(eq("run-1"), eq("ap-1"), eq(user.userId().value()), eq(""));
        mvc.perform(post("/api/v1/agent/runs/run-1/approvals/ap-1").principal(principal()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_INVALID_ARGUMENT"));
    }

    /**
     * 拒绝审批违反策略时返回 422。
     */
    @Test
    void rejectPolicyViolation() throws Exception {
        org.mockito.Mockito.doThrow(new AgentPolicyViolationException("already finished"))
                .when(runs).reject("run-1", "ap-1", user.userId().value());
        mvc.perform(post("/api/v1/agent/runs/run-1/approvals/ap-1/reject").principal(principal()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGENT_POLICY_VIOLATION"));
    }

    /**
     * 暂停路由本身不检查登录主体，请求若到达控制器则固定返回 410。
     */
    @Test
    void pauseWithoutPrincipalIsGone() throws Exception {
        mvc.perform(post("/api/v1/agent/runs/run-1/pause"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CANCEL_ONLY"));
    }

    /**
     * 构造当前测试用户的认证主体。
     */
    private UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }
}
