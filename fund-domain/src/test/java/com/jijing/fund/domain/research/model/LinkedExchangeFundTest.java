package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.model.FundCode;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link LinkedExchangeFund} 的必填字段校验。 */
class LinkedExchangeFundTest {
    private static final FundCode FUND = new FundCode("008585");
    private static final ExchangeSecurityCode ETF = new ExchangeSecurityCode("sz", "159819");
    private static final DataProvenance SOURCE = new DataProvenance(new ProviderId("eastmoney"),
            URI.create("https://fund.eastmoney.com/linked"), MarketDataKind.UNDERLYING_ETF_PROXY, "v1", null,
            Instant.parse("2026-09-01T00:00:00Z"), QualityStatus.VERIFIED, null);

    /** 基金代码、证券代码或来源为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingReferences() {
        assertThatThrownBy(() -> new LinkedExchangeFund(null, ETF, "人工智能ETF", SOURCE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("fundCode");
        assertThatThrownBy(() -> new LinkedExchangeFund(FUND, null, "人工智能ETF", SOURCE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("securityCode");
        assertThatThrownBy(() -> new LinkedExchangeFund(FUND, ETF, "人工智能ETF", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("provenance");
    }

    /** 展示名为 null 或空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsBlankDisplayName() {
        assertThatThrownBy(() -> new LinkedExchangeFund(FUND, ETF, null, SOURCE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("displayName");
        assertThatThrownBy(() -> new LinkedExchangeFund(FUND, ETF, " ", SOURCE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("displayName");
    }
}
