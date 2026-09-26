package com.jijing.fund.domain.identity;

/** 密码哈希端口，负责把明文密码转为不可逆哈希并在登录时校验，领域层不接触具体算法。 */
public interface PasswordHasher {
    /**
     * 计算明文密码的哈希值，结果可直接持久化；同一密码多次计算的结果可以不同（加盐）。
     * rawPassword 为 null 时由实现抛出异常，不会返回 null。
     */
    String hash(String rawPassword);

    /**
     * 校验明文密码是否与已存储的哈希匹配。
     * 任一参数为 null 或哈希格式无法识别时应返回 false，而不是抛出异常。
     */
    boolean matches(String rawPassword, String passwordHash);
}
