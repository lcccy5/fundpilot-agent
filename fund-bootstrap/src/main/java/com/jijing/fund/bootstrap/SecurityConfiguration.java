package com.jijing.fund.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.auth.*;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.infrastructure.security.*;
import com.jijing.fund.interfaces.web.CurrentUserArgumentResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class SecurityConfiguration implements WebMvcConfigurer {
    @Bean PasswordHasher passwordHasher(@Value("${fund.security.bcrypt-strength:12}") int strength){return new BcryptPasswordHasher(strength);}
    @Bean HmacJwtTokenCodec jwtCodec(@Value("${FUND_JWT_SIGNING_KEY:}") String key,@Value("${fund.security.issuer:jijing-agent}") String issuer,@Value("${fund.security.audience:fund-web}") String audience,@Value("${FUND_JWT_KEY_ID:local-v1}") String keyId,ObjectMapper mapper){return new HmacJwtTokenCodec(key,issuer,audience,keyId,mapper);}
    @Bean AuthUseCase authUseCase(UserAccountRepository users,PasswordHasher hashes,HmacJwtTokenCodec codec,Clock clock,@Value("${fund.security.access-token-ttl:15m}") Duration access,@Value("${fund.security.refresh-token-ttl:30d}") Duration refresh){return new AuthApplicationService(users,hashes,codec,clock,access,refresh);}
    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http,HmacJwtTokenCodec codec,UserAccountRepository users)throws Exception{
        http.csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)).cors(c->{}).exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->{res.setStatus(401);res.setContentType("application/json");res.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");})).authorizeHttpRequests(a->a
                .requestMatchers("/actuator/health","/actuator/info","/api/v1/auth/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/v1/funds/**").permitAll()
                .requestMatchers("/internal/**").hasAnyRole("ANALYST","ADMIN")
                .anyRequest().authenticated())
            .addFilterBefore(new AgentEvalTokenFilter(System.getenv().getOrDefault("FUND_AGENT_EVAL_TOKEN","local-agent-eval-token")),UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new BearerFilter(codec,users),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    @Override public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers){resolvers.add(new CurrentUserArgumentResolver());}
    private static final class BearerFilter extends OncePerRequestFilter {
        private final HmacJwtTokenCodec codec;private final UserAccountRepository users;
        BearerFilter(HmacJwtTokenCodec codec,UserAccountRepository users){this.codec=codec;this.users=users;}
        @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
            String value=r.getHeader(HttpHeaders.AUTHORIZATION);
            if(value!=null&&value.startsWith("Bearer ")){try{var verified=codec.verifyDetails(value.substring(7),Instant.now());AuthenticatedUser raw=verified.user();var account=users.findById(raw.userId()).filter(a->a.status()==UserStatus.ACTIVE&&a.tokenVersion()==verified.tokenVersion()).orElseThrow();var granted=account.roles().stream().map(role->new SimpleGrantedAuthority("ROLE_"+role.name())).toList();var auth=new UsernamePasswordAuthenticationToken(new AuthenticatedUser(account.userId(),account.roles(),raw.sessionId()),null,granted);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);}catch(Exception ignored){org.springframework.security.core.context.SecurityContextHolder.clearContext();}}
            chain.doFilter(r,s);
        }
    }
    /** Authenticates only the local evaluation action with a dedicated non-user token. */
    private static final class AgentEvalTokenFilter extends OncePerRequestFilter {
        private final byte[] expected;
        AgentEvalTokenFilter(String token){this.expected=token.getBytes(java.nio.charset.StandardCharsets.UTF_8);}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
            if("/internal/v1/agent/evaluations/execute".equals(request.getRequestURI())){
                String supplied=request.getHeader("X-Agent-Eval-Token");byte[] actual=supplied==null?new byte[0]:supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if(!java.security.MessageDigest.isEqual(expected,actual)){response.setStatus(401);response.setContentType("application/json");response.getWriter().write("{\"error\":\"EVAL_TOKEN_INVALID\"}");return;}
                var authority=new SimpleGrantedAuthority("ROLE_ADMIN");org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-eval",null,List.of(authority)));
            }
            chain.doFilter(request,response);
        }
    }
}
