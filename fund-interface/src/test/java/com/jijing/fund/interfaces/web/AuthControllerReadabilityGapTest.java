package com.jijing.fund.interfaces.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jijing.fund.application.auth.AuthException;
import com.jijing.fund.application.auth.AuthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 认证接口上尚未覆盖的参数、凭据和下游失败。
 */
@WebMvcTest(controllers = AuthController.class)
@Import({AuthController.class, RequestIdFilter.class, GlobalExceptionHandler.class, ReadabilityGapMvc.class,
        FundQueryControllerTest.TestApplication.class})
class AuthControllerReadabilityGapTest {
    @Autowired MockMvc mvc;
    @MockBean AuthUseCase auth;

    /**
     * 用户名为空白时在进入用例前返回 400。
     */
    @Test
    void registerRejectsBlankUsername() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "reg-blank")
                        .content("{\"username\":\"\",\"displayName\":\"a\",\"password\":\"secret1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.requestId").value("reg-blank"));
    }

    /**
     * 密码短于六位时返回 400，不把请求交给用例。
     */
    @Test
    void registerRejectsShortPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    /**
     * 损坏的 JSON 不走字段校验。当前会被通用异常收成 500，且不回显解析器原文。
     */
    @Test
    void malformedRegisterJsonBecomesInternalError() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 注册过程中的未分类失败返回 500，响应不包含底层异常文本。
     */
    @Test
    void registerUnexpectedFailureHidesCause() throws Exception {
        when(auth.register(any())).thenThrow(new IllegalStateException("database offline"));
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"secret1\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"));
    }

    /**
     * 口令或用户名校验失败统一返回 401。
     */
    @Test
    void loginFailureIsUnauthorized() throws Exception {
        when(auth.login(any())).thenThrow(new AuthException("invalid username or password"));
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"abc\",\"password\":\"secret1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"));
    }

    /**
     * 没有刷新 Cookie 时用例拒绝请求，返回 401。
     */
    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        when(auth.refresh(null)).thenThrow(new AuthException("refresh token is required"));
        mvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_FAILED"))
                .andExpect(jsonPath("$.message").value("refresh token is required"));
    }

    /**
     * 登出路径对匿名请求开放到控制器，缺少登录主体时返回 401。
     */
    @Test
    void logoutWithoutUserIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").header("X-Request-Id", "logout-anon"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value("logout-anon"));
    }
}
