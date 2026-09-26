package com.jijing.fund.interfaces.web;

import com.jijing.fund.application.auth.AuthUseCase;
import com.jijing.fund.application.auth.UserView;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.interfaces.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录账号的读取与停用，与注册登录生命周期分开。
 * 两条路由都要求已解析的当前用户；匿名请求返回 401。
 * 账号已不存在时同样返回 401。停用过程中的未分类异常返回 500。
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final AuthUseCase auth;

    /**
     * 绑定读取和停用当前账号所需的认证用例。
     */
    public UserController(AuthUseCase auth) {
        this.auth = auth;
    }

    /**
     * 返回当前访问令牌对应的账号视图。令牌无法解析为登录用户时返回 401。
     */
    @GetMapping("/me")
    public ApiResponse<UserView> me(@CurrentUser AuthenticatedUser user, HttpServletRequest request) {
        return ApiResponse.success(RequestIdFilter.get(request), auth.me(user));
    }

    /**
     * 停用当前账号并使其已签发令牌失效。匿名请求返回 401。
     */
    @PostMapping("/me/deactivate")
    public ApiResponse<Void> deactivate(@CurrentUser AuthenticatedUser user, HttpServletRequest request) {
        auth.deactivate(user);
        return ApiResponse.success(RequestIdFilter.get(request), null);
    }
}
