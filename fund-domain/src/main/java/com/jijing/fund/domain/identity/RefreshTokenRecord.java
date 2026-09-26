package com.jijing.fund.domain.identity;

import java.time.Instant;

/**
 * 刷新令牌的持久化记录，只保存令牌哈希而非明文；同一登录链路上轮换出的令牌共享 familyId，
 * 被轮换的旧令牌记录撤销时间和替代它的新令牌标识。构造时不做任何校验。
 */
public record RefreshTokenRecord(String tokenId, UserId userId, String familyId, String tokenHash, Instant expiresAt,
                                 Instant revokedAt, String replacedByTokenId, Instant createdAt) {
    /**
     * 判断令牌在给定时刻是否仍可使用：未被撤销且过期时间严格晚于 now，恰好等于过期时间视为已失效。
     * 未撤销时若 expiresAt 或 now 为 null 会抛出 NullPointerException；已撤销时直接返回 false，不会访问这两个值。
     */
    public boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }
}
