package com.jijing.fund.bootstrap;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 允许配置中的浏览器来源携带凭据访问公开接口和内部接口。
 * 来源名单为空时不会放开任意来源；未列入的来源由浏览器拒绝，而不是返回业务错误码。
 */
@Configuration
class WebCorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;

    /**
     * 按逗号拆分来源名单，并丢掉空白项，避免把空字符串注册成来源。
     */
    WebCorsConfiguration(@Value("${fund.web.cors-allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3001,http://127.0.0.1:3001,http://localhost:3002,http://127.0.0.1:3002,http://localhost:5173,http://127.0.0.1:5173}") String configured) {
        origins = Arrays.stream(configured.split(",")).map(String::trim).filter(value -> !value.isBlank()).toArray(String[]::new);
    }

    /**
     * 对 {@code /api/**} 和 {@code /internal/**} 使用同一套方法、请求头和凭据策略。
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
        registry.addMapping("/internal/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
