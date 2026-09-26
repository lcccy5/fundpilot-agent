package com.jijing.fund.application.auth;

/**
 * 注册所需的用户名、显示名和口令原文。
 * 本类型不校验长度或字符集；用户名不合法、口令长度越界或用户名冲突时，由认证服务抛出认证异常。
 *
 * @param username 调用方输入的用户名，服务会再做规范化
 * @param displayName 展示名称，空白时改用规范化用户名，过长时由服务截断
 * @param password 口令原文，服务只保存摘要
 */
public record RegisterCommand(String username, String displayName, String password) {
}
