package com.jijing.fund.domain.identity;

import java.time.Instant;

/** 访问令牌签发端口，把账户身份、角色和令牌版本写入一个带过期时间的短期访问令牌。 */
public interface AccessTokenIssuer {
    /**
     * 为账户在指定会话下签发访问令牌，令牌在 expiresAt 之后失效。
     * 接口不校验参数；account 或 expiresAt 为 null、签名失败时由实现抛出运行时异常，不会返回 null。
     */
    String issue(UserAccount account, String sessionId, Instant expiresAt);
}
