package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.application.auth.AuthException;
import com.jijing.fund.application.auth.AuthUseCase;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 当前账号接口的匿名、账号缺失和停用失败。
 */
@WebMvcTest(controllers = UserController.class)
@Import({UserController.class, RequestIdFilter.class, GlobalExceptionHandler.class, ReadabilityGapMvc.class,
        FundQueryControllerTest.TestApplication.class})
class UserControllerReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean AuthUseCase auth;
    private final AuthenticatedUser user = new AuthenticatedUser(
            new UserId("00000000-0000-0000-0000-000000000001"), Set.of(UserRole.USER), "session-1");

    /**
     * 没有登录主体时不能读取当前账号。
     */
    @Test
    void meWithoutUserIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 账号已从存储中消失时仍返回认证失败，而不是 404。
     */
    @Test
    void missingAccountIsAuthFailure() throws Exception {
        when(auth.me(any())).thenThrow(new AuthException("account not found"));
        mvc.perform(get("/api/v1/users/me").principal(principal()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("account not found"));
    }

    /**
     * 停用同样要求登录主体。
     */
    @Test
    void deactivateWithoutUserIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/users/me/deactivate"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    /**
     * 停用过程中的存储失败返回 500，不把底层文本回给客户端。
     */
    @Test
    void deactivateUnexpectedFailureHidesCause() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("database offline")).when(auth).deactivate(any());
        mvc.perform(post("/api/v1/users/me/deactivate").principal(principal()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 构造切片测试用的登录主体。
     */
    private UsernamePasswordAuthenticationToken principal() {
        return new UsernamePasswordAuthenticationToken(user, null, List.of());
    }
}
