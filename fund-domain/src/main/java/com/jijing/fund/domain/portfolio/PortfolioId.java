package com.jijing.fund.domain.portfolio;

import java.util.UUID;

/** 投资组合唯一标识值对象，取值必须能被解析为 UUID；保存原始字符串，不做大小写或格式规范化。 */
public record PortfolioId(String value) {
    /**
     * 校验标识格式。
     * value 为 null 或空白时抛出 IllegalArgumentException（"portfolioId is required"）；
     * 无法解析为 UUID 时由 {@link UUID#fromString} 抛出 IllegalArgumentException。
     */
    public PortfolioId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("portfolioId is required");
        }
        UUID.fromString(value);
    }

    /** 生成一个基于随机 UUID 的新组合标识，每次调用结果不同，不会抛出异常。 */
    public static PortfolioId random() {
        return new PortfolioId(UUID.randomUUID().toString());
    }
}
