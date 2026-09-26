package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link SourcedValue} 不允许缺少业务值或数据血缘。 */
class SourcedValueTest {
    private static final DataLineage LINEAGE = new DataLineage(List.of(new DataProvenance(new ProviderId("eastmoney"),
            URI.create("https://fund.eastmoney.com/nav"), MarketDataKind.OFFICIAL_FUND_NAV, "v1", null,
            Instant.parse("2026-09-01T00:00:00Z"), QualityStatus.VERIFIED, null)), null);

    /** 业务值或血缘为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingValueOrLineage() {
        assertThatThrownBy(() -> new SourcedValue<>(null, LINEAGE))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("value");
        assertThatThrownBy(() -> new SourcedValue<>("1.2345", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("lineage");
    }
}
