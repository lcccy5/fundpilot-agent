package com.jijing.fund.domain.identity;

import java.util.Set;

/**
 * 已通过认证的当前请求主体，包含用户标识、角色集合和会话标识，用于应用层做归属和权限判断。
 * userId 与 sessionId 不做校验，可为 null；角色集合在构造时做不可变拷贝。
 */
public record AuthenticatedUser(UserId userId, Set<UserRole> roles, String sessionId) {
    /**
     * 规范化角色集合：null 视为无角色（空集合），否则拷贝为不可变集合，调用方之后修改原集合不会影响本对象。
     * 角色集合中包含 null 元素时抛出 NullPointerException。
     */
    public AuthenticatedUser {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    /**
     * 判断主体是否拥有指定角色。
     * role 为 null 时，由于底层是不可变集合，会抛出 NullPointerException 而不是返回 false。
     */
    public boolean hasRole(UserRole role) {
        return roles.contains(role);
    }
}
