package com.jijing.fund.interfaces.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 请求号过滤器拒绝空白和过长的客户端取值。
 */
class RequestIdFilterReadabilityGapTest {
    /**
     * 空白请求号会被换成新的随机号，并写回响应头。
     */
    @Test
    void blankClientIdIsReplaced() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", " ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> { });
        String generated = response.getHeader("X-Request-Id");
        assertNotEquals(" ", generated);
        assertFalse(generated == null || generated.isBlank());
    }

    /**
     * 超过 128 个字符的请求号不会进入日志上下文，响应头使用新值。
     */
    @Test
    void oversizedClientIdIsReplaced() throws Exception {
        String oversized = "x".repeat(129);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", oversized);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(request, response, (req, res) -> { });
        assertNotEquals(oversized, response.getHeader("X-Request-Id"));
    }
}
