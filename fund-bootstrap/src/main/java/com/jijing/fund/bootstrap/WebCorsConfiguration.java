package com.jijing.fund.bootstrap;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebCorsConfiguration implements WebMvcConfigurer {
    private final String[] origins;
    WebCorsConfiguration(@Value("${fund.web.cors-allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://localhost:3001,http://127.0.0.1:3001,http://localhost:3002,http://127.0.0.1:3002,http://localhost:5173,http://127.0.0.1:5173}") String configured) { origins=Arrays.stream(configured.split(",")).map(String::trim).filter(value->!value.isBlank()).toArray(String[]::new); }
    @Override public void addCorsMappings(CorsRegistry registry) { registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET","POST","PATCH","PUT","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true); registry.addMapping("/internal/**").allowedOrigins(origins).allowedMethods("GET","POST","PATCH","PUT","DELETE","OPTIONS").allowedHeaders("*").allowCredentials(true); }
}
