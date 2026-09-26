package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import java.util.Set;

/**
 * 可返回给调用方的用户公开资料，不含口令摘要。
 * 账户不存在时不会构造本视图，认证服务改为抛出认证异常。
 *
 * @param userId 账户标识
 * @param username 已规范化的用户名
 * @param displayName 展示名称
 * @param roles 账户角色，注册用户默认为普通用户
 */
public record UserView(UserId userId, String username, String displayName, Set<UserRole> roles) {
}
