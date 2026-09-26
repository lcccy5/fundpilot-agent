package com.jijing.fund.application.auth;

/**
 * 注册、登录、刷新或读取当前用户失败时抛出的认证错误。
 * 消息面向调用方，不附带口令、令牌原文或内部堆栈之外的额外载荷。
 */
public class AuthException extends RuntimeException {

    /**
     * 用已经面向调用方的说明构造认证错误。
     * 不包装其他异常，说明会原样成为运行时消息。
     *
     * @param message 失败说明，例如用户名不可用、口令不符或刷新令牌无效
     */
    public AuthException(String message) {
        super(message);
    }
}
