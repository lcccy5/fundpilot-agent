package com.jijing.fund.application.auth;

import com.jijing.fund.domain.identity.AccessTokenIssuer;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.PasswordHasher;
import com.jijing.fund.domain.identity.RefreshTokenRecord;
import com.jijing.fund.domain.identity.UserAccount;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.identity.UserStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账户注册、登录、刷新令牌轮换、登出和停用的应用服务。
 * 口令与用户名在本层校验，避免绕过 HTTP 的调用方跳过界面约定；刷新令牌只保存摘要。
 */
public class AuthApplicationService implements AuthUseCase {
    private final UserAccountRepository users;
    private final PasswordHasher passwords;
    private final AccessTokenIssuer tokens;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    /**
     * 装配账户仓储、口令摘要、访问令牌签发器、时钟以及访问令牌和刷新令牌的有效期。
     * 不在构造时创建账户；有效期为 null 时留到签发会话时失败。
     */
    public AuthApplicationService(UserAccountRepository users, PasswordHasher passwords, AccessTokenIssuer tokens,
            Clock clock, Duration accessTtl, Duration refreshTtl) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    /**
     * 规范化用户名、校验口令后创建启用中的普通用户，并签发第一组会话。
     * 用户名格式不合法或口令长度越界时抛出认证异常且不访问保存；用户名已存在时抛出认证异常且不覆盖原账户。
     * 用户名为 null 时规范化结果为空串，随后会在领域账户构造处因用户名缺失失败，而不是认证异常。
     */
    @Override
    @Transactional
    public AuthSession register(RegisterCommand command) {
        String username = normalize(command.username());
        validatePassword(command.password());
        if (users.findByUsername(username).isPresent()) {
            throw new AuthException("username is unavailable");
        }
        Instant now = clock.instant();
        var account = new UserAccount(UserId.random(), username, displayName(command.displayName(), username),
                passwords.hash(command.password()), UserStatus.ACTIVE, 0, Set.of(UserRole.USER), now, now);
        users.save(account);
        return session(account);
    }

    /**
     * 查找启用中的账户并核对口令，成功后签发新会话。
     * 用户名格式不合法时抛出认证异常并说明格式要求。账户不存在、已停用或口令不符时统一抛出无效用户名或口令，不泄露账户是否存在。
     */
    @Override
    @Transactional
    public AuthSession login(LoginCommand command) {
        var account = users.findByUsername(normalize(command.username()))
                .filter(candidate -> candidate.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new AuthException("invalid username or password"));
        if (!passwords.matches(command.password(), account.passwordHash())) {
            throw new AuthException("invalid username or password");
        }
        return session(account);
    }

    /**
     * 校验刷新令牌原文，确认令牌族仍然有效且账户启用后，轮换令牌并签发新访问令牌。
     * 原文为空时抛出认证异常。摘要无法匹配时抛出认证异常。令牌已过期或已撤销时先作废整族再抛出认证异常。
     * 账户不存在或不是启用状态时抛出认证异常，且不轮换令牌。
     */
    @Override
    @Transactional
    public AuthSession refresh(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AuthException("refresh token is required");
        }
        Instant now = clock.instant();
        var old = users.findRefreshTokenByHash(hash(raw))
                .orElseThrow(() -> new AuthException("invalid refresh token"));
        if (!old.activeAt(now)) {
            users.revokeRefreshFamily(old.familyId(), now);
            throw new AuthException("refresh token is expired or revoked");
        }
        var account = users.findById(old.userId())
                .filter(candidate -> candidate.status() == UserStatus.ACTIVE)
                .orElseThrow(() -> new AuthException("account is unavailable"));
        return rotatedSession(account, old.familyId(), old.tokenId(), now);
    }

    /**
     * 撤销该用户全部刷新令牌并提升令牌版本。
     * 不确认账户是否存在；身份对象为空时在读取用户标识处失败。
     */
    @Override
    @Transactional
    public void logout(AuthenticatedUser actor) {
        Instant now = clock.instant();
        users.revokeAllRefreshTokens(actor.userId(), now);
        users.incrementTokenVersion(actor.userId(), now);
    }

    /**
     * 把账户标为停用，并撤销全部刷新令牌、提升令牌版本。
     * 不确认账户是否存在；身份对象为空时在读取用户标识处失败。
     */
    @Override
    @Transactional
    public void deactivate(AuthenticatedUser actor) {
        Instant now = clock.instant();
        users.updateStatus(actor.userId(), UserStatus.DISABLED, now);
        users.revokeAllRefreshTokens(actor.userId(), now);
        users.incrementTokenVersion(actor.userId(), now);
    }

    /**
     * 按身份中的用户标识读取账户并返回公开视图。
     * 仓储没有该用户时抛出认证异常。
     */
    @Override
    public UserView me(AuthenticatedUser actor) {
        var account = users.findById(actor.userId())
                .orElseThrow(() -> new AuthException("account not found"));
        return view(account);
    }

    /**
     * 为账户开启一组新的刷新令牌族。
     * 不复用旧令牌；签发过程的失败与轮换会话相同。
     */
    private AuthSession session(UserAccount account) {
        return rotatedSession(account, UUID.randomUUID().toString(), null, clock.instant());
    }

    /**
     * 保存新的刷新令牌摘要，可选地撤销被替换的旧令牌，并签发访问令牌。
     * 摘要算法不可用时抛出非法状态；被替换标识为空表示这是新族的第一枚令牌，不撤销任何旧记录。
     */
    private AuthSession rotatedSession(UserAccount account, String family, String replaced, Instant now) {
        String raw = UUID.randomUUID() + UUID.randomUUID().toString().replace("-", "");
        String id = UUID.randomUUID().toString();
        Instant expiry = now.plus(refreshTtl);
        users.saveRefreshToken(new RefreshTokenRecord(id, account.userId(), family, hash(raw), expiry, null, null, now));
        if (replaced != null) {
            users.revokeRefreshToken(replaced, id, now);
        }
        String sessionId = id;
        Instant accessExpiry = now.plus(accessTtl);
        return new AuthSession(account.userId(), tokens.issue(account, sessionId, accessExpiry), raw, accessExpiry,
                view(account));
    }

    /**
     * 计算刷新令牌原文的 SHA-256 十六进制摘要，仓储只保存摘要。
     * 运行环境缺少 SHA-256 时抛出非法状态，不把算法异常泄漏为认证失败。
     */
    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 去掉空白并转为小写，再检查是否为三到六十四位允许字符。
     * 文本为 null 时返回空串且不抛出认证异常；其余不匹配允许字符的值抛出认证异常。
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9_.-]{3,64}")) {
            throw new AuthException("username must be 3-64 lowercase letters, numbers, dot, dash or underscore");
        }
        return normalized;
    }

    /**
     * 按注册界面与接口约定检查口令长度，避免非 HTTP 调用方绕过。
     * 口令为 null、短于六个字符或长于一百二十八个字符时抛出认证异常。
     */
    private static void validatePassword(String value) {
        if (value == null || value.length() < 6 || value.length() > 128) {
            throw new AuthException("password must be 6-128 characters");
        }
    }

    /**
     * 得到可展示的名称。空白时退回用户名；超过八十个字符时截断。
     * 不抛出业务异常；显示名为 null 时按空白处理。
     */
    private static String displayName(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) {
            return fallback;
        }
        if (trimmed.length() > 80) {
            return trimmed.substring(0, 80);
        }
        return trimmed;
    }

    /**
     * 把账户映射为不含口令摘要的公开视图。
     * 账户为空时在读取字段处失败。
     */
    private static UserView view(UserAccount account) {
        return new UserView(account.userId(), account.normalizedUsername(), account.displayName(), account.roles());
    }
}
