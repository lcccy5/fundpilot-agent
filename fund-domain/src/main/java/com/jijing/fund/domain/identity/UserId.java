package com.jijing.fund.domain.identity;

import java.util.UUID;

/** 用户唯一标识值对象，取值必须能被解析为 UUID；保存原始字符串，不做大小写或格式规范化。 */
public record UserId(String value) {
    /**
     * 校验标识格式。
     * value 为 null 或空白时抛出 IllegalArgumentException（"userId is required"）；
     * 无法解析为 UUID 时由 {@link UUID#fromString} 抛出 IllegalArgumentException。
     */
    public UserId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        UUID.fromString(value);
    }

    /** 生成一个基于随机 UUID 的新用户标识，每次调用结果不同，不会抛出异常。 */
    public static UserId random() {
        return new UserId(UUID.randomUUID().toString());
    }
}
