package com.jijing.fund.domain.research.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** 沪深交易所证券代码值对象，交易所前缀统一规范为小写的 sh 或 sz，证券代码为 6 位数字。 */
public record ExchangeSecurityCode(String exchange, String code) {
    private static final Pattern CODE = Pattern.compile("\\d{6}");

    /**
     * 规范化交易所并校验代码。
     * exchange 或 code 为 null 时抛出 NullPointerException；交易所去除空白并转小写后不是 sh/sz（例如 bj）时抛出
     * IllegalArgumentException；code 不是 6 位数字时抛出 IllegalArgumentException，code 不会被去除空白。
     */
    public ExchangeSecurityCode {
        Objects.requireNonNull(exchange, "exchange must not be null");
        Objects.requireNonNull(code, "code must not be null");
        exchange = exchange.trim().toLowerCase(Locale.ROOT);
        if (!exchange.equals("sh") && !exchange.equals("sz")) throw new IllegalArgumentException("exchange must be sh or sz");
        if (!CODE.matcher(code).matches()) throw new IllegalArgumentException("security code must be 6 digits");
    }

    /** 返回行情提供方使用的拼接代码（如 sz159819），构造后始终合法，不会抛出异常。 */
    public String providerSymbol() {
        return exchange + code;
    }
}
