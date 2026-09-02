package com.jijing.fund.interfaces.web;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;

public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {
    @Override public boolean supportsParameter(MethodParameter p){return p.hasParameterAnnotation(CurrentUser.class)&&p.getParameterType()==AuthenticatedUser.class;}
    @Override public Object resolveArgument(MethodParameter p,ModelAndViewContainer m,NativeWebRequest r,WebDataBinderFactory b){Authentication a=(Authentication)r.getUserPrincipal();if(a==null||!(a.getPrincipal() instanceof AuthenticatedUser user))throw new org.springframework.security.access.AccessDeniedException("authentication required");return user;}
}
