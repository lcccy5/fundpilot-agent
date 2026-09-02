package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.auth.*;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthUseCase auth;private final boolean refreshCookieSecure;private final Duration refreshTokenTtl;
    public AuthController(AuthUseCase auth,@org.springframework.beans.factory.annotation.Value("${fund.security.refresh-cookie-secure:true}") boolean refreshCookieSecure,@org.springframework.beans.factory.annotation.Value("${fund.security.refresh-token-ttl:30d}") Duration refreshTokenTtl){this.auth=auth;this.refreshCookieSecure=refreshCookieSecure;this.refreshTokenTtl=refreshTokenTtl;}
    @PostMapping("/register") public ResponseEntity<ApiResponse<AuthBody>> register(@Valid @RequestBody RegisterBody body,HttpServletRequest request,HttpServletResponse response){return write(auth.register(new RegisterCommand(body.username(),body.displayName(),body.password())),request,response,HttpStatus.CREATED);}
    @PostMapping("/login") public ResponseEntity<ApiResponse<AuthBody>> login(@Valid @RequestBody LoginBody body,HttpServletRequest request,HttpServletResponse response){return write(auth.login(new LoginCommand(body.username(),body.password())),request,response,HttpStatus.OK);}
    @PostMapping("/refresh") public ResponseEntity<ApiResponse<AuthBody>> refresh(@CookieValue(name="fund_refresh",required=false) String refresh,HttpServletRequest request,HttpServletResponse response){return write(auth.refresh(refresh),request,response,HttpStatus.OK);}
    @PostMapping("/logout") public ResponseEntity<ApiResponse<Void>> logout(@CurrentUser AuthenticatedUser user,HttpServletRequest request,HttpServletResponse response){auth.logout(user);clear(response);return ResponseEntity.ok(ApiResponse.success(RequestIdFilter.get(request),null));}
    private ResponseEntity<ApiResponse<AuthBody>> write(AuthSession session,HttpServletRequest request,HttpServletResponse response,HttpStatus status){setRefresh(response,session.refreshToken());return ResponseEntity.status(status).body(ApiResponse.success(RequestIdFilter.get(request),new AuthBody(session.accessToken(),session.accessTokenExpiresAt().toString(),session.user())));}
    private void setRefresh(HttpServletResponse response,String value){ResponseCookie cookie=ResponseCookie.from("fund_refresh",value).httpOnly(true).secure(refreshCookieSecure).sameSite("Lax").path("/api/v1/auth").maxAge(refreshTokenTtl).build();response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());}
    private void clear(HttpServletResponse response){response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from("fund_refresh","").httpOnly(true).secure(refreshCookieSecure).sameSite("Lax").path("/api/v1/auth").maxAge(Duration.ZERO).build().toString());}
    public record RegisterBody(@NotBlank @Size(max=64) String username,@Size(max=80) String displayName,@NotBlank @Size(min=10,max=128) String password){}
    public record LoginBody(@NotBlank @Size(max=64) String username,@NotBlank @Size(max=128) String password){}
    public record AuthBody(String accessToken,String accessTokenExpiresAt,UserView user){}
}
