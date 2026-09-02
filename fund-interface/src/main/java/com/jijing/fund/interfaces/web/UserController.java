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

/** Current-account endpoint is intentionally separate from the authentication lifecycle. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final AuthUseCase auth;
    public UserController(AuthUseCase auth){this.auth=auth;}

    @GetMapping("/me")
    public ApiResponse<UserView> me(@CurrentUser AuthenticatedUser user,HttpServletRequest request){
        return ApiResponse.success(RequestIdFilter.get(request),auth.me(user));
    }

    @PostMapping("/me/deactivate")
    public ApiResponse<Void> deactivate(@CurrentUser AuthenticatedUser user,HttpServletRequest request){
        auth.deactivate(user);return ApiResponse.success(RequestIdFilter.get(request),null);
    }
}
