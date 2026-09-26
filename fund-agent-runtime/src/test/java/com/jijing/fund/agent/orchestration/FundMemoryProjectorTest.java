package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 确认净值投影保留边界和指定日期，长文本只在提示词视图里截断。 存储中的原文不被投影改写。 */
class FundMemoryProjectorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final FundMemoryProjector projector = new FundMemoryProjector(mapper);

  /** 长净值序列抽样后仍包含首尾日期。 抽样失败不会回退成全量数组。 */
  @Test
  void samplesALargeNavSeriesButKeepsItsBoundaries() {
    ObjectNode source = navHistory(100);

    var projected = projector.project(FundMemoryCategory.NAV, source, "看看这只基金的净值走势");

    ArrayNode items = (ArrayNode) projected.get("items");
    assertThat(items).hasSize(8);
    assertThat(items.get(0).get("navDate").asText()).isEqualTo("2026-01-01");
    assertThat(items.get(items.size() - 1).get("navDate").asText()).isEqualTo("2026-04-10");
    assertThat(TokenBudgetChatMemory.estimateTokens(projected.toString())).isLessThan(500);
  }

  /** 点名日期时保留该日以及区间两端。 非法日期不会选中额外行。 */
  @Test
  void keepsTheRequestedNavDateAlongsideRangeBoundaries() {
    ObjectNode source = navHistory(100);

    var projected = projector.project(FundMemoryCategory.NAV, source, "2026年2月10日的累计净值是多少？");

    ArrayNode items = (ArrayNode) projected.get("items");
    assertThat(items)
        .extracting(item -> item.get("navDate").asText())
        .containsExactly("2026-01-01", "2026-02-10", "2026-04-10");
  }

  /** 既有日期又问走势时仍给出抽样序列。 不会只剩单独一天。 */
  @Test
  void aDatedTrendQuestionStillReceivesASampledSeries() {
    ObjectNode source = navHistory(100);

    var projected =
        projector.project(FundMemoryCategory.NAV, source, "看看2026年1月1日至2026年4月10日的净值走势");

    assertThat((ArrayNode) projected.get("items")).hasSize(8);
  }

  /** 超长摘录在投影中截断，源 JSON 保持原长。 截断不使投影失败。 */
  @Test
  void truncatesOversizedDocumentTextOnlyInThePromptView() {
    ObjectNode source = mapper.createObjectNode().put("excerpt", "研究资料".repeat(1000));

    var projected = projector.project(FundMemoryCategory.DOCUMENTS, source, "投资策略是什么");

    assertThat(projected.get("excerpt").asText()).hasSize(801).endsWith("…");
    assertThat(source.get("excerpt").asText()).hasSize(4000);
  }

  /** 生成指定天数的净值序列供投影使用。 日期不合法时测试数据本身就会失败，不代表运行时吞掉了投影错误。 */
  private ObjectNode navHistory(int days) {
    ObjectNode result =
        mapper.createObjectNode().put("fundCode", "000001").put("dataSource", "test");
    ArrayNode items = result.putArray("items");
    LocalDate start = LocalDate.of(2026, 1, 1);
    for (int i = 0; i < days; i++) {
      items
          .addObject()
          .put("navDate", start.plusDays(i).toString())
          .put("unitNav", 1 + i / 1000D)
          .put("accumulatedNav", 2 + i / 1000D);
    }
    return result;
  }
}
