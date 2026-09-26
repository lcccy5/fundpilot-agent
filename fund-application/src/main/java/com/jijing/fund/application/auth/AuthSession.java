package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;

/**
 * 一次成功登录、注册或刷新后交给调用方的会话。
 * 刷新令牌原文只出现在这里，仓储保存的是摘要；令牌失效后再次使用由刷新流程拒绝。
 *
 * @param userId 账户标识
 * @param accessToken 访问令牌原文
 * @param refreshToken 刷新令牌原文，轮换后旧值即失效
 * @param accessTokenExpiresAt 访问令牌到期时刻
 * @param user 不含口令的用户视图
 */
public record AuthSession(UserId userId, String accessToken, String refreshToken, Instant accessTokenExpiresAt,
        UserView user) {
}
