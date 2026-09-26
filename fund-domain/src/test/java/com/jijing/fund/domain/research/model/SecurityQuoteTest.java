package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link SecurityQuote} 的必填字段、正价格约束以及可选字段允许缺失。 */
class SecurityQuoteTest {
    private static final ExchangeSecurityCode CODE = new ExchangeSecurityCode("sz", "159819");
    private static final Instant NOW = Instant.parse("2026-09-01T01:30:00Z");
    private static final DataProvenance QUOTE_SOURCE = new DataProvenance(new ProviderId("tencent-quote"),
            URI.create("https://qt.gtimg.cn/q"), MarketDataKind.EXCHANGE_TRADED_QUOTE, "v1", NOW, NOW,
            QualityStatus.VERIFIED, null);

    /** 用给定名称和现价构造行情，其余字段取合法默认值。 */
    private static SecurityQuote quote(String name, BigDecimal price) {
        return new SecurityQuote(CODE, name, price, null, null, null, NOW, QUOTE_SOURCE);
    }

    /** 现价为 0 或负数时抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonPositivePrice() {
        assertThatThrownBy(() -> quote("人工智能ETF", BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> quote("人工智能ETF", new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    }

    /** 证券名称为 null 或空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> quote(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> quote("  ", BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
    }

    /** 证券代码、现价、行情时间或来源为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new SecurityQuote(null, "ETF", BigDecimal.ONE, null, null, null, NOW, QUOTE_SOURCE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("securityCode");
        assertThatThrownBy(() -> quote("ETF", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("currentPrice");
        assertThatThrownBy(() -> new SecurityQuote(CODE, "ETF", BigDecimal.ONE, null, null, null, null, QUOTE_SOURCE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("quoteTime");
        assertThatThrownBy(() -> new SecurityQuote(CODE, "ETF", BigDecimal.ONE, null, null, null, NOW, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("provenance");
    }

    /** 昨收、涨跌额和涨跌幅可以缺失。 */
    @Test
    void allowsMissingOptionalPriceFields() {
        assertThatCode(() -> quote("人工智能ETF", new BigDecimal("1.234"))).doesNotThrowAnyException();
    }
}
