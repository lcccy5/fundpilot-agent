package com.jijing.fund.infrastructure.security;

import com.jijing.fund.domain.identity.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt 口令哈希。强度在构造时固定。没有网络超时或连接失败。
 * 原文或已存哈希为 null 时比对直接返回 false，不抛异常。
 */
public class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder;

    /** 强度越高校验越慢，调用方应在配置里选定后保持不变。 */
    public BcryptPasswordHasher(int strength) {
        encoder = new BCryptPasswordEncoder(strength);
    }

    /** 每次哈希都带新盐，相同口令不会得到相同字符串。 */
    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    /** null 视为不匹配，避免编码器对空入参抛错。 */
    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && passwordHash != null && encoder.matches(rawPassword, passwordHash);
    }
}
