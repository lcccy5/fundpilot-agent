package com.jijing.fund.domain.identity;

import java.time.Instant;
import java.util.Set;

/**
 * 用户账户聚合，保存登录名、展示名、密码哈希、账户状态、令牌版本和角色；
 * 令牌版本递增后，旧版本的访问令牌应被视为失效。
 */
public record UserAccount(UserId userId, String normalizedUsername, String displayName, String passwordHash,
                          UserStatus status, int tokenVersion, Set<UserRole> roles, Instant createdAt, Instant updatedAt) {
    /**
     * 校验登录名并规范化角色集合。
     * normalizedUsername 为 null 或空白时抛出 IllegalArgumentException；roles 为 null 视为空集合，否则做不可变拷贝，
     * 含 null 元素时抛出 NullPointerException。其余字段（包括 userId 和 status）不校验，可为 null。
     */
    public UserAccount {
        if (normalizedUsername == null || normalizedUsername.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
