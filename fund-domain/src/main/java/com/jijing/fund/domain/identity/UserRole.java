package com.jijing.fund.domain.identity;

/**
 * 用户角色，决定可访问的功能范围；按名称持久化并写入访问令牌，
 * 因此不能随意重命名，未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum UserRole { USER, ANALYST, ADMIN }
