package com.jijing.fund.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.jijing.fund.domain.research.model.DataLineage;
import com.jijing.fund.domain.research.model.DataProvenance;
import com.jijing.fund.domain.research.model.MarketDataKind;
import com.jijing.fund.domain.research.model.ProviderId;
import com.jijing.fund.domain.research.model.QualityStatus;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolEvidenceFactoryTest {
    @Test
    void preservesUnderlyingEtfProxyTypeAndUsesStableEvidenceId() {
        var provenance = new DataProvenance(new ProviderId("tencent-quote"), URI.create("https://qt.gtimg.cn/q"),
                MarketDataKind.UNDERLYING_ETF_PROXY, "20260827093000", Instant.parse("2026-08-27T01:30:00Z"),
                Instant.parse("2026-08-27T01:31:00Z"), QualityStatus.VERIFIED, List.of());
        var lineage = new DataLineage(List.of(provenance), null);
        var factory = new ToolEvidenceFactory();

        var first = factory.create("fund_realtime_quote", "000001", lineage);
        var second = factory.create("fund_realtime_quote", "000001", lineage);

        assertThat(first).hasSize(1);
        assertThat(first.getFirst().evidenceType()).isEqualTo("UNDERLYING_ETF_PROXY");
        assertThat(first.getFirst().navBasis()).isEqualTo("UNDERLYING_ETF_PROXY");
        assertThat(first.getFirst().evidenceId()).isEqualTo(second.getFirst().evidenceId());
    }
}
