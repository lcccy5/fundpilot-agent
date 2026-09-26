package com.jijing.fund.interfaces.web;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 只解析标了 {@link CurrentUser} 且类型为登录用户的参数。
 * 匿名请求或主体不是登录用户时拒绝访问，避免把其它主体误当成账号。
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    /**
     * 仅承接当前用户注解，其它参数交给默认解析器。
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType() == AuthenticatedUser.class;
    }

    /**
     * 从请求主体取出登录用户。缺失或类型不符时抛出拒绝访问，映射为 401。
     */
    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication authentication = (Authentication) webRequest.getUserPrincipal();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new AccessDeniedException("authentication required");
        }
        return user;
    }
}
