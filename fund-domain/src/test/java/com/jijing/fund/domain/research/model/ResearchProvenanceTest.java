package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证研究数据的来源约束：行情不能冒充官方净值，来源地址不能携带密钥。 */
class ResearchProvenanceTest {
    /** 数据含义标为官方净值的来源不能用于构造场内行情，构造时抛出 IllegalArgumentException。 */
    @Test
    void exchangeQuoteCannotPretendToBeOfficialFundNav() {
        var provenance = new DataProvenance(new ProviderId("tencent-quote"), URI.create("https://qt.gtimg.cn/q"),
                MarketDataKind.OFFICIAL_FUND_NAV, "20260827093000", Instant.parse("2026-08-27T01:30:00Z"),
                Instant.parse("2026-08-27T01:31:00Z"), QualityStatus.VERIFIED, null);

        assertThatThrownBy(() -> new SecurityQuote(new ExchangeSecurityCode("sz", "159819"), "人工智能ETF",
                java.math.BigDecimal.ONE, null, null, null, Instant.parse("2026-08-27T01:30:00Z"), provenance))
                .hasMessageContaining("EXCHANGE_TRADED_QUOTE");
    }

    /** 带查询参数（可能含 apiKey）的来源地址被拒绝，防止密钥进入审计记录。 */
    @Test
    void provenanceRejectsSecretBearingSourceUri() {
        assertThatThrownBy(() -> new DataProvenance(new ProviderId("tencent-quote"),
                URI.create("https://example.com/q?apiKey=secret"), MarketDataKind.EXCHANGE_TRADED_QUOTE, "v1",
                null, Instant.now(), QualityStatus.VERIFIED, null)).hasMessageContaining("query parameters");
    }
}
