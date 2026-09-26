package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证 {@link ExchangeSecurityCode} 对交易所前缀的规范化和对证券代码的严格校验。 */
class ExchangeSecurityCodeTest {
    /** 交易所前缀去除空白并转小写，拼接后的提供方代码使用规范化前缀。 */
    @Test
    void normalizesExchange() {
        var code = new ExchangeSecurityCode(" SZ ", "159819");

        assertThat(code.exchange()).isEqualTo("sz");
        assertThat(code.providerSymbol()).isEqualTo("sz159819");
    }

    /** 沪深以外的交易所（如北交所 bj）和空前缀被拒绝。 */
    @Test
    void rejectsUnsupportedExchange() {
        assertThatThrownBy(() -> new ExchangeSecurityCode("bj", "430047"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sh or sz");
        assertThatThrownBy(() -> new ExchangeSecurityCode("", "600000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sh or sz");
    }

    /** 证券代码不是 6 位数字，或带首尾空白（代码不会被去空白）时被拒绝。 */
    @Test
    void rejectsInvalidCode() {
        assertThatThrownBy(() -> new ExchangeSecurityCode("sh", "60000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6 digits");
        assertThatThrownBy(() -> new ExchangeSecurityCode("sh", " 600000"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6 digits");
        assertThatThrownBy(() -> new ExchangeSecurityCode("sh", "60000a"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("6 digits");
    }

    /** 交易所或代码为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> new ExchangeSecurityCode(null, "600000"))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("exchange");
        assertThatThrownBy(() -> new ExchangeSecurityCode("sh", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("code");
    }
}
