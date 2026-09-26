package com.jijing.fund.application.auth;

/**
 * 登录所需的用户名和口令原文。
 * 本类型不校验格式；用户名为空、口令不匹配或账户不可用时，由认证服务抛出认证异常。
 *
 * @param username 调用方输入的用户名，服务会再做规范化
 * @param password 口令原文，不会被本类型保存为摘要
 */
public record LoginCommand(String username, String password) {
}
