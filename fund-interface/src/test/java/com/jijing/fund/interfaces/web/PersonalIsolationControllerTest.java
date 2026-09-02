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

    @Test void userBCannotReadAnotherPortfolioById()throws Exception{
        when(portfolios.positions(eq(userB),any())).thenThrow(new PortfolioNotFoundException("not found"));
        mvc.perform(get("/api/v1/portfolios/00000000-0000-0000-0000-000000000aaa/positions").principal(auth(userB)).header("X-Request-Id","idor-1"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
        verify(portfolios).positions(eq(userB),eq(new PortfolioId("00000000-0000-0000-0000-000000000aaa")));
    }

    @Test void userBCannotReadAnotherWatchlistById()throws Exception{
        when(watchlists.rename(eq(userB),eq("group-a"),eq("x"),eq(1L))).thenThrow(new WatchlistNotFoundException("not found"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/watchlists/group-a")
                        .principal(auth(userB)).contentType("application/json").content("{\"name\":\"x\",\"version\":1}").header("X-Request-Id","idor-2"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PERSONAL_RESOURCE_NOT_FOUND"));
    }

    @Test void pauseAndResumeAreGoneCancelOnly()throws Exception{
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/agent/runs/run-a/pause").principal(auth(userB)).header("X-Request-Id","pause-1"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CANCEL_ONLY"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/agent/runs/run-a/resume").principal(auth(userB)).header("X-Request-Id","resume-1"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CANCEL_ONLY"));
    }

    @Test void lastEventIdIsPassedToEventQuery()throws Exception{
        when(runs.events(eq("run-a"),eq(userB.userId().value()),eq(7L)))
                .thenReturn(List.of(new com.jijing.fund.agent.api.AgentRunEventView("e8",8L,"task.completed","{}",java.time.Instant.parse("2026-08-27T08:00:00Z"))));
        mvc.perform(get("/api/v1/agent/runs/run-a/events").principal(auth(userB)).header("Last-Event-ID","7").header("X-Request-Id","sse-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].sequence").value(8));
        verify(runs).events("run-a",userB.userId().value(),7L);
    }

    @Test void mcpCapabilitiesAreAdminOnly()throws Exception{
        when(mcp.schemaHash()).thenReturn("abc");
        when(mcp.tools()).thenReturn(Set.of("get_fund_profile"));
        mvc.perform(get("/api/v1/mcp/capabilities").principal(auth(userB)).header("X-Request-Id","mcp-user"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
        mvc.perform(get("/api/v1/mcp/capabilities").principal(auth(admin)).header("X-Request-Id","mcp-admin"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.schemaHash").value("abc"));
    }

    @Test void userBCannotReadAnotherAgentRun()throws Exception{
        when(runs.get("run-a",userB.userId().value())).thenThrow(new AgentRunNotFoundException("run not found"));
        mvc.perform(get("/api/v1/agent/runs/run-a").principal(auth(userB)).header("X-Request-Id","idor-3"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AGENT_RUN_NOT_FOUND"));
        verify(runs).get("run-a",userB.userId().value());
    }

    private UsernamePasswordAuthenticationToken auth(AuthenticatedUser user){return new UsernamePasswordAuthenticationToken(user,null,List.of());}

    static class Mvc implements WebMvcConfigurer {
        @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers){resolvers.add(new CurrentUserArgumentResolver());}
    }
}
