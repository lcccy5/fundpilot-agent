package com.jijing.fund.application.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jijing.fund.domain.identity.AccessTokenIssuer;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.PasswordHasher;
import com.jijing.fund.domain.identity.RefreshTokenRecord;
import com.jijing.fund.domain.identity.UserAccount;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.identity.UserStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 补齐认证服务尚未覆盖的未认证、账户缺失、用户名冲突和非法命令失败路径。
 */
class AuthApplicationServiceReadabilityGapTest {
    private static final Instant NOW = Instant.parse("2026-08-27T08:00:00Z");

    private FakeUsers users;
    private AuthApplicationService service;

    /**
     * 使用固定时钟和可观察的口令摘要，便于断言失败时没有写入账户。
     */
    @BeforeEach
    void setUp() {
        users = new FakeUsers();
        PasswordHasher passwords = new PasswordHasher() {
            @Override
            public String hash(String rawPassword) {
                return "hash:" + rawPassword;
            }

            @Override
            public boolean matches(String rawPassword, String passwordHash) {
                return passwordHash.equals("hash:" + rawPassword);
            }
        };
        AccessTokenIssuer tokens = (account, sessionId, expiresAt) -> "access-" + account.normalizedUsername();
        service = new AuthApplicationService(users, passwords, tokens, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15), Duration.ofDays(30));
    }

    /**
     * 未知用户、停用账户和错误口令都表现为未认证，并且不签发新令牌。
     */
    @Test
    void rejectsUnauthenticatedLogin() {
        AuthException unknown = assertThrows(AuthException.class,
                () -> service.login(new LoginCommand("alice", "secret1")));
        assertEquals("invalid username or password", unknown.getMessage());

        service.register(new RegisterCommand("alice", "Alice", "secret1"));
        users.disable("alice");
        AuthException disabled = assertThrows(AuthException.class,
                () -> service.login(new LoginCommand("alice", "secret1")));
        assertEquals("invalid username or password", disabled.getMessage());

        users.enable("alice");
        AuthException wrongPassword = assertThrows(AuthException.class,
                () -> service.login(new LoginCommand("alice", "wrong-pass")));
        assertEquals("invalid username or password", wrongPassword.getMessage());
        assertEquals(1, users.accounts.size());
    }

    /**
     * 刷新令牌缺失、无法识别、已过期，或账户已停用时拒绝轮换。过期令牌还会作废整族。
     */
    @Test
    void rejectsUnauthenticatedRefresh() {
        assertThrows(AuthException.class, () -> service.refresh(null));
        AuthException blank = assertThrows(AuthException.class, () -> service.refresh("  "));
        assertEquals("refresh token is required", blank.getMessage());

        AuthException unknown = assertThrows(AuthException.class, () -> service.refresh("missing-token"));
        assertEquals("invalid refresh token", unknown.getMessage());

        AuthSession session = service.register(new RegisterCommand("alice", "Alice", "secret1"));
        users.expireTokens(NOW);
        int tokensBefore = users.tokens.size();
        AuthException expired = assertThrows(AuthException.class, () -> service.refresh(session.refreshToken()));
        assertEquals("refresh token is expired or revoked", expired.getMessage());
        assertEquals(List.of(users.tokens.values().iterator().next().familyId()), users.revokedFamilies);
        assertEquals(tokensBefore, users.tokens.size());

        users.restoreTokenExpiry(NOW.plus(Duration.ofDays(30)));
        users.revokedFamilies.clear();
        users.disable("alice");
        AuthException unavailable = assertThrows(AuthException.class, () -> service.refresh(session.refreshToken()));
        assertEquals("account is unavailable", unavailable.getMessage());
        assertTrue(users.revokedFamilies.isEmpty());
    }

    /**
     * 当前身份对应的账户不存在时，读取资料失败。
     */
    @Test
    void rejectsMissingAccountWhenReadingCurrentUser() {
        AuthenticatedUser actor = new AuthenticatedUser(
                new UserId("00000000-0000-0000-0000-000000000099"), Set.of(UserRole.USER), "sid");

        AuthException missing = assertThrows(AuthException.class, () -> service.me(actor));

        assertEquals("account not found", missing.getMessage());
    }

    /**
     * 规范化后相同的用户名不能再次注册，原账户保持不变。
     */
    @Test
    void rejectsConflictingUsername() {
        service.register(new RegisterCommand("Alice", "Alice", "secret1"));

        AuthException conflict = assertThrows(AuthException.class,
                () -> service.register(new RegisterCommand("alice", "Other", "secret1")));

        assertEquals("username is unavailable", conflict.getMessage());
        assertEquals(1, users.accounts.size());
        assertEquals("Alice", users.accounts.get("alice").displayName());
    }

    /**
     * 用户名字符不合法、口令过短、口令过长或口令缺失时拒绝注册，并且不保存账户。
     * 用户名为 null 时当前不会变成认证异常，而是在领域账户构造处失败。
     */
    @Test
    void rejectsInvalidRegistrationCommand() {
        AuthException shortName = assertThrows(AuthException.class,
                () -> service.register(new RegisterCommand("ab", "Ann", "secret1")));
        AuthException shortPassword = assertThrows(AuthException.class,
                () -> service.register(new RegisterCommand("alice", "Alice", "short")));
        AuthException longPassword = assertThrows(AuthException.class,
                () -> service.register(new RegisterCommand("alice", "Alice", "x".repeat(129))));
        AuthException missingPassword = assertThrows(AuthException.class,
                () -> service.register(new RegisterCommand("alice", "Alice", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.register(new RegisterCommand(null, "Alice", "secret1")));

        assertEquals("username must be 3-64 lowercase letters, numbers, dot, dash or underscore",
                shortName.getMessage());
        assertEquals("password must be 6-128 characters", shortPassword.getMessage());
        assertEquals("password must be 6-128 characters", longPassword.getMessage());
        assertEquals("password must be 6-128 characters", missingPassword.getMessage());
        assertTrue(users.accounts.isEmpty());
    }

    /**
     * 登录时格式不合法的用户名直接说明格式要求，而不是伪装成口令错误。
     */
    @Test
    void rejectsMalformedUsernameOnLoginAsInvalidCommand() {
        AuthException malformed = assertThrows(AuthException.class,
                () -> service.login(new LoginCommand("ab", "secret1")));

        assertEquals("username must be 3-64 lowercase letters, numbers, dot, dash or underscore",
                malformed.getMessage());
    }

    private static final class FakeUsers implements UserAccountRepository {
        private final Map<String, UserAccount> accounts = new HashMap<>();
        private final Map<String, RefreshTokenRecord> tokens = new HashMap<>();
        private final List<String> revokedFamilies = new ArrayList<>();

        @Override
        public Optional<UserAccount> findByUsername(String normalizedUsername) {
            return Optional.ofNullable(accounts.get(normalizedUsername));
        }

        @Override
        public Optional<UserAccount> findById(UserId userId) {
            return accounts.values().stream().filter(account -> account.userId().equals(userId)).findFirst();
        }

        @Override
        public void save(UserAccount account) {
            accounts.put(account.normalizedUsername(), account);
        }

        @Override
        public void saveRefreshToken(RefreshTokenRecord token) {
            tokens.put(token.tokenHash(), token);
        }

        @Override
        public Optional<RefreshTokenRecord> findRefreshTokenByHash(String hash) {
            return Optional.ofNullable(tokens.get(hash));
        }

        @Override
        public void revokeRefreshFamily(String familyId, Instant revokedAt) {
            revokedFamilies.add(familyId);
        }

        @Override
        public void revokeRefreshToken(String tokenId, String replacementTokenId, Instant revokedAt) {
        }

        @Override
        public void incrementTokenVersion(UserId userId, Instant updatedAt) {
        }

        @Override
        public void revokeAllRefreshTokens(UserId userId, Instant revokedAt) {
        }

        @Override
        public void updateStatus(UserId userId, UserStatus status, Instant updatedAt) {
        }

        private void disable(String username) {
            replaceStatus(username, UserStatus.DISABLED);
        }

        private void enable(String username) {
            replaceStatus(username, UserStatus.ACTIVE);
        }

        private void replaceStatus(String username, UserStatus status) {
            UserAccount account = accounts.get(username);
            accounts.put(username, new UserAccount(account.userId(), account.normalizedUsername(),
                    account.displayName(), account.passwordHash(), status, account.tokenVersion(), account.roles(),
                    account.createdAt(), account.updatedAt()));
        }

        private void expireTokens(Instant expiresAt) {
            replaceExpiry(expiresAt);
        }

        private void restoreTokenExpiry(Instant expiresAt) {
            replaceExpiry(expiresAt);
        }

        private void replaceExpiry(Instant expiresAt) {
            Map<String, RefreshTokenRecord> updated = new HashMap<>();
            for (RefreshTokenRecord token : tokens.values()) {
                updated.put(token.tokenHash(), new RefreshTokenRecord(token.tokenId(), token.userId(),
                        token.familyId(), token.tokenHash(), expiresAt, token.revokedAt(), token.replacedByTokenId(),
                        token.createdAt()));
            }
            tokens.clear();
            tokens.putAll(updated);
        }
    }
}
