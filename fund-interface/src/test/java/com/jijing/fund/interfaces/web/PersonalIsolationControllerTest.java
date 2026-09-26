package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.application.portfolio.PortfolioNotFoundException;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.watchlist.WatchlistNotFoundException;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.portfolio.PortfolioId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 核对组合、自选、运行和 MCP 的属主隔离，以及暂停恢复已下线。
 */
@WebMvcTest(controllers={PortfolioController.class,WatchlistController.class,FundAgentController.class,McpCapabilitiesController.class})
@Import({PortfolioController.class,WatchlistController.class,FundAgentController.class,McpCapabilitiesController.class,RequestIdFilter.class,GlobalExceptionHandler.class,PersonalIsolationControllerTest.Mvc.class,FundQueryControllerTest.TestApplication.class})
class PersonalIsolationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean PortfolioUseCase portfolios;
    @MockBean WatchlistUseCase watchlists;
    @MockBean com.jijing.fund.agent.api.FundAgentUseCase chat;
    @MockBean AgentRunUseCase runs;
    @MockBean com.jijing.fund.agent.mcp.FundMcpServer mcp;
    private final AuthenticatedUser userB=new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000002"),Set.of(UserRole.USER),"s-b");
    private final AuthenticatedUser admin=new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000009"),Set.of(UserRole.ADMIN),"s-admin");

    /**
     * 其他用户的组合持仓按不存在返回，避免泄露是否存在。
     */
    @Test void userBCannotReadAnotherPortfolioById()throws Exception{
        when(portfolios.positions(eq(userB),any())).thenThrow(new PortfolioNotFoundException("not found"));
        mvc.perform(get("/api/v1/portfolios/00000000-0000-0000-0000-000000000aaa/positions").principal(auth(userB)).header("X-Request-Id","idor-1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
        verify(portfolios).positions(eq(userB),eq(new PortfolioId("00000000-0000-0000-0000-000000000aaa")));
    }

    /**
     * 其他用户的自选分组不能被重命名。
     */
    @Test void userBCannotReadAnotherWatchlistById()throws Exception{
        when(watchlists.rename(eq(userB),eq("group-a"),eq("x"),eq(1L))).thenThrow(new WatchlistNotFoundException("not found"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/watchlists/group-a")
                        .principal(auth(userB)).contentType("application/json").content("{\"name\":\"x\",\"version\":1}").header("X-Request-Id","idor-2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
    }

    /**
     * 暂停和恢复固定返回已下线，提示改走取消。
     */
    @Test void pauseAndResumeAreGoneCancelOnly()throws Exception{
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/agent/runs/run-a/pause").principal(auth(userB)).header("X-Request-Id","pause-1"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CANCEL_ONLY"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/agent/runs/run-a/resume").principal(auth(userB)).header("X-Request-Id","resume-1"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CANCEL_ONLY"));
    }

    /**
     * Last-Event-ID 会作为事件游标传给运行查询。
     */
    @Test void lastEventIdIsPassedToEventQuery()throws Exception{
        when(runs.events(eq("run-a"),eq(userB.userId().value()),eq(7L)))
                .thenReturn(List.of(new com.jijing.fund.agent.api.AgentRunEventView("e8",8L,"task.completed","{}",java.time.Instant.parse("2026-08-27T08:00:00Z"))));
        mvc.perform(get("/api/v1/agent/runs/run-a/events").principal(auth(userB)).header("Last-Event-ID","7").header("X-Request-Id","sse-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sequence").value(8));
        verify(runs).events("run-a",userB.userId().value(),7L);
    }

    /**
     * 普通用户看不到 MCP 清单，管理员可以。
     */
    @Test void mcpCapabilitiesAreAdminOnly()throws Exception{
        when(mcp.schemaHash()).thenReturn("abc");
        when(mcp.tools()).thenReturn(Set.of("get_fund_profile"));
        mvc.perform(get("/api/v1/mcp/capabilities").principal(auth(userB)).header("X-Request-Id","mcp-user"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(get("/api/v1/mcp/capabilities").principal(auth(admin)).header("X-Request-Id","mcp-admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.schemaHash").value("abc"));
    }

    /**
     * 其他用户的运行按不存在返回。
     */
    @Test void userBCannotReadAnotherAgentRun()throws Exception{
        when(runs.get("run-a",userB.userId().value())).thenThrow(new AgentRunNotFoundException("run not found"));
        mvc.perform(get("/api/v1/agent/runs/run-a").principal(auth(userB)).header("X-Request-Id","idor-3"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
        verify(runs).get("run-a",userB.userId().value());
    }

    /**
     * 把登录用户包成切片请求可用的认证主体。
     */
    private UsernamePasswordAuthenticationToken auth(AuthenticatedUser user){return new UsernamePasswordAuthenticationToken(user,null,List.of());}

    /**
     * 为隔离测试注册当前用户参数解析器。
     */
    static class Mvc implements WebMvcConfigurer {
        /**
         * 追加当前用户解析器，不移除框架默认解析器。
         */
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers){resolvers.add(new CurrentUserArgumentResolver());}
    }
}
