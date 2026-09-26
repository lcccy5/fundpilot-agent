package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link DataLineage} 至少需要一个非 null 输入来源。 */
class DataLineageTest {
    private static final DataProvenance INPUT = new DataProvenance(new ProviderId("eastmoney"),
            URI.create("https://fund.eastmoney.com/nav"), MarketDataKind.OFFICIAL_FUND_NAV, "v1", null,
            Instant.parse("2026-09-01T00:00:00Z"), QualityStatus.VERIFIED, null);

    /** 输入为 null、空列表或只含 null 元素时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingInputs() {
        assertThatThrownBy(() -> new DataLineage(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one input");
        assertThatThrownBy(() -> new DataLineage(List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one input");
        assertThatThrownBy(() -> new DataLineage(Arrays.asList(null, null), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one input");
    }

    /** null 输入被过滤掉，推导说明允许缺失，结果列表不可修改。 */
    @Test
    void filtersNullInputsAndAllowsMissingDerivation() {
        var lineage = new DataLineage(Arrays.asList(null, INPUT), null);

        assertThat(lineage.inputs()).containsExactly(INPUT);
        assertThat(lineage.derivation()).isNull();
        assertThatThrownBy(() -> lineage.inputs().add(INPUT)).isInstanceOf(UnsupportedOperationException.class);
    }
}
