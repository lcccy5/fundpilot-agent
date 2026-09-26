package com.jijing.fund.bootstrap;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.application.auth.AuthUseCase;
import com.jijing.fund.domain.identity.UserAccount;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.identity.UserStatus;
import com.jijing.fund.infrastructure.security.HmacJwtTokenCodec;
import com.jijing.fund.interfaces.web.AuthController;
import com.jijing.fund.interfaces.web.CurrentUserArgumentResolver;
import com.jijing.fund.interfaces.web.FundAgentController;
import com.jijing.fund.interfaces.web.GlobalExceptionHandler;
import com.jijing.fund.interfaces.web.RequestIdFilter;
import com.jijing.fund.interfaces.web.UserController;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全过滤链上的匿名拒绝、无效令牌、角色不足和评测令牌失败。
 * 受保护路由的 401 正文是入口点 JSON，不是统一信封。
 */
class SecurityFailureReadabilityGapTest {
    private static final String SIGNING_KEY = "0123456789abcdef0123456789abcdef";
    private static AnnotationConfigApplicationContext context;
    private static Validator validator;
    private MockMvc mvc;

    /**
     * 只启动安全配置和它的直接依赖，不扫描整个应用。
     */
    @BeforeAll
    static void boot() {
        context = new AnnotationConfigApplicationContext();
        context.register(SecurityConfiguration.class, Support.class);
        context.getEnvironment().getSystemProperties().put("FUND_JWT_SIGNING_KEY", SIGNING_KEY);
        context.getEnvironment().getSystemProperties().put("fund.security.bcrypt-strength", "4");
        context.refresh();
        LocalValidatorFactoryBean factory = new LocalValidatorFactoryBean();
        factory.afterPropertiesSet();
        validator = factory;
    }

    /**
     * 关闭手工容器，避免后续测试复用同一批单例。
     */
    @AfterAll
    static void shutdown() {
        if (context != null) {
            context.close();
        }
    }

    /**
     * 把认证、当前用户、暂停路由和内部探针接到同一条过滤链后面。
     */
    @BeforeEach
    void mvc() {
        AuthUseCase auth = context.getBean(AuthUseCase.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new AuthController(auth, false, Duration.ofDays(30)),
                        new UserController(auth),
                        new FundAgentController(mock(FundAgentUseCase.class), mock(AgentRunUseCase.class)),
                        new InternalProbe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setValidator(validator)
                .addFilters(new FilterChainProxy(context.getBean(SecurityFilterChain.class)), new RequestIdFilter())
                .build();
        org.mockito.Mockito.reset(context.getBean(UserAccountRepository.class));
    }

    /**
     * 受保护的当前用户接口在过滤器处返回入口点 401。
     */
    @Test
    void protectedRouteWithoutTokenUsesEntryPoint() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * 无法校验的 Bearer 令牌同样走入口点，而不是进入控制器。
     */
    @Test
    void invalidBearerUsesEntryPoint() throws Exception {
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * 认证路径允许匿名到达控制器。没有登录主体的登出返回统一信封 401。
     */
    @Test
    void anonymousLogoutReachesCurrentUserResolver() throws Exception {
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 登录口令无法匹配任何账号时返回 401。
     */
    @Test
    void loginUnknownUserIsAuthFailure() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"secret1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    /**
     * 注册密码过短时返回 400，过滤链不会把它当成未认证。
     */
    @Test
    void registerShortPasswordIsBadRequest() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 没有刷新 Cookie 时刷新接口返回 401。
     */
    @Test
    void refreshWithoutCookieIsAuthFailure() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    /**
     * 暂停路由在生产过滤链上先要求登录，匿名请求到不了控制器的 410。
     */
    @Test
    void anonymousPauseIsRejectedBeforeGone() throws Exception {
        mvc.perform(post("/api/v1/agent/runs/run-1/pause"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    /**
     * 普通用户访问内部接口返回 403。
     */
    @Test
    void userRoleCannotCallInternalProbe() throws Exception {
        UserAccount account = account(UserRole.USER);
        when(context.getBean(UserAccountRepository.class).findById(account.userId())).thenReturn(Optional.of(account));
        mvc.perform(get("/internal/v1/probe").header("Authorization", "Bearer " + token(account)))
                .andExpect(status().isForbidden());
    }

    /**
     * 分析师可以通过内部接口的角色校验。
     */
    @Test
    void analystCanCallInternalProbe() throws Exception {
        UserAccount account = account(UserRole.ANALYST);
        when(context.getBean(UserAccountRepository.class).findById(account.userId())).thenReturn(Optional.of(account));
        mvc.perform(get("/internal/v1/probe").header("Authorization", "Bearer " + token(account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    /**
     * 评测执行缺少专用令牌时，过滤器直接返回 401，正文不是统一信封。
     */
    @Test
    void evaluationWithoutTokenIsRejected() throws Exception {
        mvc.perform(post("/internal/v1/agent/evaluations/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("EVAL_TOKEN_INVALID")));
    }

    /**
     * 评测令牌正确时不再返回令牌错误；本切片没有评测控制器，因此继续得到 404。
     */
    @Test
    void evaluationWithTokenPassesTheFilter() throws Exception {
        mvc.perform(post("/internal/v1/agent/evaluations/execute")
                        .header("X-Agent-Eval-Token", "local-agent-eval-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    /**
     * 造一个指定角色、令牌版本为 0 的启用账号。
     */
    private static UserAccount account(UserRole role) {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        String id = role == UserRole.ADMIN ? "00000000-0000-0000-0000-0000000000ad"
                : role == UserRole.ANALYST ? "00000000-0000-0000-0000-0000000000aa"
                : "00000000-0000-0000-0000-0000000000ab";
        return new UserAccount(new UserId(id), role.name().toLowerCase(), role.name(), "{noop}",
                UserStatus.ACTIVE, 0, Set.of(role), now, now);
    }

    /**
     * 用容器里的编码器签发一支尚未过期的访问令牌。
     */
    private static String token(UserAccount account) {
        return context.getBean(HmacJwtTokenCodec.class).issue(account, "sid-" + account.userId().value(),
                Instant.now().plusSeconds(600));
    }

    /**
     * 提供时钟、账号仓储和 JSON 映射，并打开安全过滤链所需的 HTTP 安全建造器。
     */
    @Configuration
    @EnableWebSecurity
    static class Support {
        /**
         * 固定使用系统 UTC 时钟。登录失败路径不会依赖具体时刻。
         */
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        /**
         * 用假的账号仓储避免连接数据库。
         */
        @Bean
        UserAccountRepository users() {
            return mock(UserAccountRepository.class);
        }

        /**
         * 供访问令牌编码器读写声明。
         */
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /**
     * 只用来区分内部接口的 403 和放行后的成功。
     */
    @RestController
    static class InternalProbe {
        /**
         * 角色校验通过后返回固定正文。
         */
        @GetMapping("/internal/v1/probe")
        public Map<String, String> probe() {
            return Map.of("status", "ok");
        }
    }
}
