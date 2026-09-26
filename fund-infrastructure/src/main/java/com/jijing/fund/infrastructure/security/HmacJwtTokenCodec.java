package com.jijing.fund.infrastructure.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.domain.identity.AccessTokenIssuer;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserAccount;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 内部 HS256 访问令牌。签名密钥只留在内存，不写入日志或数据库。
 * 密钥短于 32 个字符时拒绝构造。签发或校验失败分别抛出非法状态或非法参数。
 * 过期、签发者或受众不符、签名不符都当成无效令牌。没有网络超时、空响应或重复提交。
 */
public final class HmacJwtTokenCodec implements AccessTokenIssuer {
    private final byte[] key;
    private final String issuer;
    private final String audience;
    private final String keyId;
    private final ObjectMapper mapper;

    /** 密钥按 UTF-8 字节参与 HMAC，不做额外派生。 */
    public HmacJwtTokenCodec(String secret, String issuer, String audience, String keyId, ObjectMapper mapper) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("JWT signing key must be at least 32 characters");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.audience = audience;
        this.keyId = keyId;
        this.mapper = mapper;
    }

    /** 载荷含主体、会话、令牌版本和角色。签发时间取调用时的系统时钟。 */
    @Override
    public String issue(UserAccount account, String sessionId, Instant expiresAt) {
        try {
            String header = b64(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT", "kid", keyId)));
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("sub", account.userId().value());
            claims.put("iss", issuer);
            claims.put("aud", audience);
            claims.put("sid", sessionId);
            claims.put("tv", account.tokenVersion());
            claims.put("roles", account.roles().stream().map(Enum::name).sorted().toList());
            claims.put("iat", Instant.now().getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            String body = b64(mapper.writeValueAsBytes(claims));
            String signed = header + "." + body;
            return signed + "." + b64(hmac(signed));
        } catch (Exception e) {
            throw new IllegalStateException("cannot issue access token", e);
        }
    }

    /** 只返回用户身份，令牌版本由 {@link #verifyDetails} 保留。 */
    public AuthenticatedUser verify(String token, Instant now) {
        return verifyDetails(token, now).user();
    }

    /**
     * 签名用常量时间比较。过期时刻小于等于当前秒即失效。
     * 缺少主体、会话或令牌版本时拒绝，角色列表非法同样拒绝。
     */
    public VerifiedAccessToken verifyDetails(String token, Instant now) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3 || !MessageDigest.isEqual(hmac(parts[0] + "." + parts[1]), b64d(parts[2]))) {
                throw new IllegalArgumentException("invalid access token");
            }
            Map<String, Object> claims = mapper.readValue(b64d(parts[1]), new TypeReference<>() {
            });
            if (!issuer.equals(claims.get("iss")) || !audience.equals(claims.get("aud"))
                    || ((Number) claims.get("exp")).longValue() <= now.getEpochSecond()) {
                throw new IllegalArgumentException("access token expired");
            }
            Set<UserRole> roles = new HashSet<>();
            Object raw = claims.get("roles");
            if (raw instanceof Collection<?> values) {
                for (Object value : values) {
                    roles.add(UserRole.valueOf(String.valueOf(value)));
                }
            }
            Object tokenVersion = claims.get("tv");
            if (!(tokenVersion instanceof Number) || claims.get("sub") == null || claims.get("sid") == null) {
                throw new IllegalArgumentException("invalid access token claims");
            }
            AuthenticatedUser user = new AuthenticatedUser(new UserId(String.valueOf(claims.get("sub"))), roles,
                    String.valueOf(claims.get("sid")));
            return new VerifiedAccessToken(user, ((Number) tokenVersion).intValue());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid access token", e);
        }
    }

    /** HS256。密钥规格每次新建，避免跨调用共享 Mac 实例。 */
    private byte[] hmac(String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    /** URL 安全且不补等号，符合 JWT 常见写法。 */
    private static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    /** 解码签名或载荷。格式错误由调用方收成无效令牌。 */
    private static byte[] b64d(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    /** 校验通过后的用户和令牌版本，供会话层比对是否已被吊销。 */
    public record VerifiedAccessToken(AuthenticatedUser user, int tokenVersion) {
        /**
         * 组件原样保存。用户对象由校验过程构造。
         */
        public VerifiedAccessToken {
        }
    }
}
