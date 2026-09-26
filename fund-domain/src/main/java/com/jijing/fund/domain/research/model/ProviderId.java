package com.jijing.fund.domain.research.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 数据提供方标识，用于来源追溯、治理和指标统计；规范化为小写字母开头、由小写字母、数字和连字符组成的 2~63 位字符串。 */
public record ProviderId(String value) {
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9-]{1,62}");

    /**
     * 去除首尾空白并转小写后校验格式。
     * value 为 null 时抛出 NullPointerException；规范化后长度不在 2~63、不以字母开头或含下划线、空格等其他字符时抛出 IllegalArgumentException。
     */
    public ProviderId {
        Objects.requireNonNull(value, "providerId must not be null");
        value = value.trim().toLowerCase(Locale.ROOT);
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("providerId must use lowercase letters, digits and hyphens");
        }
    }
}
