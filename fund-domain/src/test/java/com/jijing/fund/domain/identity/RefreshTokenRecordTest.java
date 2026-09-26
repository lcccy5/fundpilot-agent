package com.jijing.fund.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link RefreshTokenRecord#activeAt} 在撤销、过期边界和缺失时间时的判断。 */
class RefreshTokenRecordTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 用给定过期时间和撤销时间构造一条刷新令牌记录，其余字段取合法默认值。 */
    private static RefreshTokenRecord token(Instant expiresAt, Instant revokedAt) {
        return new RefreshTokenRecord("t1", UserId.random(), "f1", "hash", expiresAt, revokedAt, null, NOW);
    }

    /** 未撤销且尚未过期的令牌可用。 */
    @Test
    void activeWhenNotRevokedAndNotExpired() {
        assertThat(token(NOW.plusSeconds(60), null).activeAt(NOW)).isTrue();
    }

    /** 已撤销的令牌即使尚未过期也不可用。 */
    @Test
    void inactiveWhenRevoked() {
        assertThat(token(NOW.plusSeconds(60), NOW.minusSeconds(1)).activeAt(NOW)).isFalse();
    }

    /** 恰好到达过期时间即视为失效，过期之后同样失效。 */
    @Test
    void inactiveAtAndAfterExpiry() {
        assertThat(token(NOW, null).activeAt(NOW)).isFalse();
        assertThat(token(NOW.minusSeconds(1), null).activeAt(NOW)).isFalse();
    }

    /** 未撤销时过期时间或查询时刻为 null 会抛出 NullPointerException。 */
    @Test
    void nullTimesFailWhenNotRevoked() {
        assertThatThrownBy(() -> token(null, null).activeAt(NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> token(NOW.plusSeconds(60), null).activeAt(null)).isInstanceOf(NullPointerException.class);
    }

    /** 已撤销时直接返回 false，不会因为缺失过期时间而抛出异常。 */
    @Test
    void revokedTokenShortCircuitsMissingExpiry() {
        assertThat(token(null, NOW).activeAt(null)).isFalse();
    }
}
