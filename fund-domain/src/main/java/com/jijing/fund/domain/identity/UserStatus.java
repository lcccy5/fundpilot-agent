package com.jijing.fund.domain.identity;

/**
 * 用户账户状态，按名称持久化；应用层只允许 ACTIVE 账户登录和刷新令牌，DISABLED 表示已停用，
 * LEGACY 为历史遗留状态，同样不能登录。未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum UserStatus { ACTIVE, DISABLED, LEGACY }
