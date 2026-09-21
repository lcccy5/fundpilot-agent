package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FundMemoryProjectorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final FundMemoryProjector projector = new FundMemoryProjector(mapper);

    @Test void samplesALargeNavSeriesButKeepsItsBoundaries() {
        ObjectNode source = navHistory(100);

        var projected = projector.project(FundMemoryCategory.NAV, source, "看看这只基金的净值走势");

        ArrayNode items = (ArrayNode) projected.get("items");
        assertThat(items).hasSize(8);
        assertThat(items.get(0).get("navDate").asText()).isEqualTo("2026-01-01");
        assertThat(items.get(items.size() - 1).get("navDate").asText()).isEqualTo("2026-04-10");
        assertThat(TokenBudgetChatMemory.estimateTokens(projected.toString())).isLessThan(500);
    }

    @Test void keepsTheRequestedNavDateAlongsideRangeBoundaries() {
        ObjectNode source = navHistory(100);

        var projected = projector.project(FundMemoryCategory.NAV, source,
                "2026年2月10日的累计净值是多少？");

        ArrayNode items = (ArrayNode) projected.get("items");
        assertThat(items).extracting(item -> item.get("navDate").asText())
                .containsExactly("2026-01-01", "2026-02-10", "2026-04-10");
    }

    @Test void aDatedTrendQuestionStillReceivesASampledSeries() {
        ObjectNode source = navHistory(100);

        var projected = projector.project(FundMemoryCategory.NAV, source,
                "看看2026年1月1日至2026年4月10日的净值走势");

        assertThat((ArrayNode) projected.get("items")).hasSize(8);
    }

    @Test void truncatesOversizedDocumentTextOnlyInThePromptView() {
        ObjectNode source = mapper.createObjectNode().put("excerpt", "研究资料".repeat(1000));

        var projected = projector.project(FundMemoryCategory.DOCUMENTS, source, "投资策略是什么");

        assertThat(projected.get("excerpt").asText()).hasSize(801).endsWith("…");
        assertThat(source.get("excerpt").asText()).hasSize(4000);
    }

    private ObjectNode navHistory(int days) {
        ObjectNode result = mapper.createObjectNode().put("fundCode", "000001").put("dataSource", "test");
        ArrayNode items = result.putArray("items");
        LocalDate start = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < days; i++) {
            items.addObject().put("navDate", start.plusDays(i).toString())
                    .put("unitNav", 1 + i / 1000D).put("accumulatedNav", 2 + i / 1000D);
        }
        return result;
    }
}
