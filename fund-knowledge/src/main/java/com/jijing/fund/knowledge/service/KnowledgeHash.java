package com.jijing.fund.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 入库和切块共用的 SHA-256 十六进制摘要。摘要使用小写十六进制，没有分隔符。
 */
public final class KnowledgeHash {
    /**
     * 禁止实例化。
     */
    private KnowledgeHash() {}

    /**
     * 计算字节的 SHA-256。摘要算法不可用时包装成 {@link IllegalStateException}。
     */
    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 按 UTF-8 字节计算字符串的 SHA-256。
     */
    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}
