package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.auth.AuthSession;
import com.jijing.fund.application.auth.AuthUseCase;
import com.jijing.fund.application.auth.LoginCommand;
import com.jijing.fund.application.auth.RegisterCommand;
import com.jijing.fund.application.auth.UserView;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号注册、登录、刷新与登出。
 * 路径前缀 {@code /api/v1/auth} 在安全配置中对匿名请求开放。
 * 请求体缺字段、超长或密码过短时返回 400；用户名不可用、口令错误或刷新凭据无效时返回 401；
 * 登出依赖已解析的当前用户，匿名调用返回 401。未分类异常返回 500，且不回显内部细节。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthUseCase auth;
    private final boolean refreshCookieSecure;
    private final Duration refreshTokenTtl;

    /**
     * 绑定认证用例，以及刷新 Cookie 的 Secure 标记和有效期。
     */
    public AuthController(AuthUseCase auth,
            @Value("${fund.security.refresh-cookie-secure:true}") boolean refreshCookieSecure,
            @Value("${fund.security.refresh-token-ttl:30d}") Duration refreshTokenTtl) {
        this.auth = auth;
        this.refreshCookieSecure = refreshCookieSecure;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    /**
     * 创建账号并下发访问令牌与刷新 Cookie。成功时返回 201。
     * 用户名已被占用时返回 401，而不是 409。
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthBody>> register(@Valid @RequestBody RegisterBody body,
            HttpServletRequest request, HttpServletResponse response) {
        return write(auth.register(new RegisterCommand(body.username(), body.displayName(), body.password())),
                request, response, HttpStatus.CREATED);
    }

    /**
     * 校验口令并轮换会话。失败一律返回 401，不区分用户不存在和口令错误。
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthBody>> login(@Valid @RequestBody LoginBody body,
            HttpServletRequest request, HttpServletResponse response) {
        return write(auth.login(new LoginCommand(body.username(), body.password())), request, response, HttpStatus.OK);
    }

    /**
     * 用 {@code fund_refresh} Cookie 轮换访问令牌。Cookie 缺失、过期或已撤销时返回 401。
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthBody>> refresh(
            @CookieValue(name = "fund_refresh", required = false) String refresh,
            HttpServletRequest request, HttpServletResponse response) {
        return write(auth.refresh(refresh), request, response, HttpStatus.OK);
    }

    /**
     * 吊销当前用户的刷新令牌并清除 Cookie。没有可解析的登录态时返回 401。
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@CurrentUser AuthenticatedUser user,
            HttpServletRequest request, HttpServletResponse response) {
        auth.logout(user);
        clear(response);
        return ResponseEntity.ok(ApiResponse.success(RequestIdFilter.get(request), null));
    }

    /**
     * 把刷新令牌写入 HttpOnly Cookie，并只把访问令牌放进响应体。
     */
    private ResponseEntity<ApiResponse<AuthBody>> write(AuthSession session, HttpServletRequest request,
            HttpServletResponse response, HttpStatus status) {
        setRefresh(response, session.refreshToken());
        return ResponseEntity.status(status).body(ApiResponse.success(RequestIdFilter.get(request),
                new AuthBody(session.accessToken(), session.accessTokenExpiresAt().toString(), session.user())));
    }

    /**
     * 刷新 Cookie 限定在认证路径，避免随普通业务请求发送。
     */
    private void setRefresh(HttpServletResponse response, String value) {
        ResponseCookie cookie = ResponseCookie.from("fund_refresh", value)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(refreshTokenTtl)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 用同名、同路径、零寿命的 Cookie 覆盖浏览器中的刷新凭据。
     */
    private void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("fund_refresh", "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Lax")
                .path("/api/v1/auth")
                .maxAge(Duration.ZERO)
                .build()
                .toString());
    }

    /**
     * 注册请求体。展示名可省略，用户名和密码必填。
     */
    public record RegisterBody(@NotBlank @Size(max = 64) String username,
            @Size(max = 80) String displayName,
            @NotBlank @Size(min = 6, max = 128) String password) {}

    /**
     * 登录请求体。
     */
    public record LoginBody(@NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {}

    /**
     * 返回给浏览器的访问令牌材料。刷新令牌只出现在 Cookie 中。
     */
    public record AuthBody(String accessToken, String accessTokenExpiresAt, UserView user) {}
}
