package com.jijing.fund.interfaces.web;

import java.util.List;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 给切片测试注册当前用户解析器，让匿名请求在进入控制器前被拒绝。
 */
class ReadabilityGapMvc implements WebMvcConfigurer {
    /**
     * 追加解析器，不替换框架自带的参数解析。
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
