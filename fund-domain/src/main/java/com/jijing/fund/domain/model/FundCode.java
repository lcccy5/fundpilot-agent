package com.jijing.fund.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;

/** 基金代码值对象，当前只接受中国公募基金常见的 6 位 ASCII 数字代码，不会自动去除空白或补零。 */
public record FundCode(String value) {
    private static final Pattern PATTERN = Pattern.compile("^\\d{6}$");

    /**
     * 校验代码格式。
     * value 为 null 时抛出 NullPointerException；长度不是 6、包含非数字字符（含首尾空白、全角数字）时抛出 IllegalArgumentException。
     */
    public FundCode {
        Objects.requireNonNull(value, "fundCode must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("fundCode must be exactly 6 digits");
        }
    }
}
