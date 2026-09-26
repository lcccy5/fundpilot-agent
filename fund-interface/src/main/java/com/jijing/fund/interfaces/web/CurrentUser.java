package com.jijing.fund.interfaces.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需要从安全上下文解析出的当前登录用户。
 * 主体缺失或类型不符时，参数解析器抛出拒绝访问，并由统一异常处理返回 401。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {}
