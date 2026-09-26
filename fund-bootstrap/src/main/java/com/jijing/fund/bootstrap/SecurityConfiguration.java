package com.jijing.fund.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.auth.AuthApplicationService;
import com.jijing.fund.application.auth.AuthUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.PasswordHasher;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserStatus;
import com.jijing.fund.infrastructure.security.BcryptPasswordHasher;
import com.jijing.fund.infrastructure.security.HmacJwtTokenCodec;
import com.jijing.fund.interfaces.web.CurrentUserArgumentResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 无状态访问令牌、密码哈希，以及接口的匿名、登录和内部角色边界。
 * 未携带可接受令牌访问受保护路由时，入口返回 401 且正文是 {@code {"error":"UNAUTHORIZED"}}，不是统一信封。
 * 已登录但角色不足时走安全框架默认的 403。本地评测令牌错误时过滤器直接返回 401 和 {@code EVAL_TOKEN_INVALID}。
 */
@Configuration
class SecurityConfiguration implements WebMvcConfigurer {
    /**
     * 按配置的成本创建密码哈希器。成本过低只应出现在测试配置中。
     */
    @Bean
    PasswordHasher passwordHasher(@Value("${fund.security.bcrypt-strength:12}") int strength) {
        return new BcryptPasswordHasher(strength);
    }

    /**
     * 创建 HMAC 访问令牌编解码器。签名密钥短于 32 个字符时，构造器会拒绝启动。
     */
    @Bean
    HmacJwtTokenCodec jwtCodec(@Value("${FUND_JWT_SIGNING_KEY:}") String key,
            @Value("${fund.security.issuer:jijing-agent}") String issuer,
            @Value("${fund.security.audience:fund-web}") String audience,
            @Value("${FUND_JWT_KEY_ID:local-v1}") String keyId, ObjectMapper mapper) {
        return new HmacJwtTokenCodec(key, issuer, audience, keyId, mapper);
    }

    /**
     * 装配注册、登录、刷新和停用。时钟由应用配置提供，便于测试固定时间。
     */
    @Bean
    AuthUseCase authUseCase(UserAccountRepository users, PasswordHasher hashes, HmacJwtTokenCodec codec, Clock clock,
            @Value("${fund.security.access-token-ttl:15m}") Duration access,
            @Value("${fund.security.refresh-token-ttl:30d}") Duration refresh) {
        return new AuthApplicationService(users, hashes, codec, clock, access, refresh);
    }

    /**
     * 健康检查、认证接口和基金只读查询允许匿名。内部接口要求分析师或管理员。
     * 异步和错误分发放行，避免已经开始的事件流在后续派发时被重新拒绝。
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, HmacJwtTokenCodec codec, UserAccountRepository users)
            throws Exception {
        // 事件流在进入异步处理前已经完成认证；只放行容器的后续派发，避免 Flux 推送或结束时被再次拒绝。
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(c -> {})
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
                }))
                .authorizeHttpRequests(a -> a
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info", "/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/funds/**").permitAll()
                        .requestMatchers("/internal/**").hasAnyRole("ANALYST", "ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new AgentEvalTokenFilter(System.getenv().getOrDefault("FUND_AGENT_EVAL_TOKEN",
                        "local-agent-eval-token")), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new BearerFilter(codec, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 让控制器可以用注解拿到登录用户，而不是各自读取安全上下文。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }

    /**
     * 校验 Bearer 访问令牌，并确认账号仍启用且令牌版本未失效。
     * 任何校验失败都清空上下文并继续过滤链，由后面的授权规则决定 401 还是匿名放行。
     */
    private static final class BearerFilter extends OncePerRequestFilter {
        private final HmacJwtTokenCodec codec;
        private final UserAccountRepository users;

        /**
         * 绑定令牌编解码器和账号查询，过滤器本身不签发令牌。
         */
        BearerFilter(HmacJwtTokenCodec codec, UserAccountRepository users) {
            this.codec = codec;
            this.users = users;
        }

        /**
         * 只处理 Bearer 方案。令牌无效、账号停用或版本不一致时当作未登录继续向后传递。
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String value = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (value != null && value.startsWith("Bearer ")) {
                try {
                    var verified = codec.verifyDetails(value.substring(7), Instant.now());
                    AuthenticatedUser raw = verified.user();
                    var account = users.findById(raw.userId())
                            .filter(candidate -> candidate.status() == UserStatus.ACTIVE
                                    && candidate.tokenVersion() == verified.tokenVersion())
                            .orElseThrow();
                    var granted = account.roles().stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                            .toList();
                    var auth = new UsernamePasswordAuthenticationToken(
                            new AuthenticatedUser(account.userId(), account.roles(), raw.sessionId()), null, granted);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (Exception ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
            chain.doFilter(request, response);
        }
    }

    /**
     * 只用独立的评测令牌放行本地评测动作，不把它当成普通用户登录。
     */
    private static final class AgentEvalTokenFilter extends OncePerRequestFilter {
        private final byte[] expected;

        /**
         * 保存期望令牌的字节，便于用常数时间比较。
         */
        AgentEvalTokenFilter(String token) {
            this.expected = token.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * 仅拦截评测执行路径。令牌不一致时直接写 401 并停止过滤链；一致时授予管理员角色后继续。
         */
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if ("/internal/v1/agent/evaluations/execute".equals(request.getRequestURI())) {
                String supplied = request.getHeader("X-Agent-Eval-Token");
                byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, actual)) {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"EVAL_TOKEN_INVALID\"}");
                    return;
                }
                var authority = new SimpleGrantedAuthority("ROLE_ADMIN");
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("agent-eval", null, List.of(authority)));
            }
            chain.doFilter(request, response);
        }
    }
}
