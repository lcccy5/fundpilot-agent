package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 确认记忆选择只带回问题需要的基金和分区，并排除过期或区间不符的卡片。 选不中时提示词不包含这些事实，回答必须重新调用工具。 */
class FundMemorySelectorTest {
  private final Instant now = Instant.parse("2026-09-10T02:00:00Z");
  private final FundMemorySelector selector = new FundMemorySelector(new ObjectMapper());

  /** 指代基金经理时只选中对应基金的概况卡。 其他基金和指标不会进入提示词。 */
  @Test
  void selectsOnlyTheRequestedFundAndCategory() {
    var state =
        new AgentConversationState(
            "c", "000001", List.of("000001", "110022"), null, null, "PROFILE", now);
    var selected =
        selector.select(
            "它的基金经理是谁？",
            state,
            List.of(
                card("profile-1", "get_fund_profile", "000001", "{\"manager\":\"张三\"}"),
                card("metrics-1", "calculate_fund_metrics", "000001", "{\"returnRate\":0.1}"),
                card("profile-2", "get_fund_profile", "110022", "{\"manager\":\"李四\"}")),
            3,
            1200);
    assertThat(selected.fundCount()).isEqualTo(1);
    assertThat(selected.cardIds()).containsExactly("profile-1");
    assertThat(selected.prompt())
        .contains("fund=000001", "张三", "observedAt", "validUntil", "evidenceIds")
        .doesNotContain("returnRate", "李四");
  }

  /** 同一只基金的多个相关分区合成一张卡片。 超预算或分区不符的卡不会混进去。 */
  @Test
  void assemblesMultipleRequestedSectionsIntoOneCardPerFund() {
    var state = new AgentConversationState("c", "000001", List.of("000001"), null, null, null, now);
    var selected =
        selector.select(
            "全面分析一下 000001",
            state,
            List.of(
                card("profile", "get_fund_profile", "000001", "{\"type\":\"混合型\"}"),
                card("metrics", "calculate_fund_metrics", "000001", "{\"maxDrawdown\":-0.1}")),
            3,
            1200);
    assertThat(selected.fundCount()).isEqualTo(1);
    assertThat(selected.cardIds()).containsExactlyInAnyOrder("profile", "metrics");
    assertThat(selected.prompt()).contains("profile", "metrics");
  }

  /** 比较多只基金时每只基金一张组装卡。 不相关主体被排除。 */
  @Test
  void returnsOneAssembledCardForEachComparedFund() {
    var state =
        new AgentConversationState(
            "c", "110022", List.of("000001", "110022"), null, null, "METRICS", now);
    var selected =
        selector.select(
            "这两只基金的最大回撤谁更小？",
            state,
            List.of(
                card("a", "calculate_fund_metrics", "000001", "{\"maxDrawdown\":-0.1}"),
                card("b", "calculate_fund_metrics", "110022", "{\"maxDrawdown\":-0.2}")),
            3,
            1200);
    assertThat(selected.fundCount()).isEqualTo(2);
    assertThat(selected.prompt()).contains("fund=000001", "fund=110022");
  }

  /** 区间相差过大的指标卡不进入提示词。 避免把错误区间的数字当成当前事实。 */
  @Test
  void rejectsMetricsFromADifferentPeriod() {
    var state =
        new AgentConversationState(
            "c",
            "000001",
            List.of("000001"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 8, 31),
            "METRICS",
            now);
    var evidence =
        new EvidenceReference(
            "ev",
            "FUND_METRICS",
            "000001",
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 12, 31),
            "ACCUMULATED_NAV",
            null,
            "v1",
            "a1",
            now);
    var wrong =
        new AgentFactCard(
            "wrong",
            "c",
            "r",
            "calculate_fund_metrics",
            "000001",
            List.of(evidence),
            "{\"maxDrawdown\":-0.1}",
            now,
            now.plusSeconds(60));
    var selected = selector.select("它同期的最大回撤呢？", state, List.of(wrong), 3, 1200);
    assertThat(selected.cardIds()).isEmpty();
  }

  /** 大净值序列经投影后仍能放进较紧的预算。 放不下的基金会被跳过，而不是截断半行。 */
  @Test
  void aLargeNavCardCanBeReusedUnderATightPromptBudget() throws Exception {
    var data = new ObjectMapper().createObjectNode().put("fundCode", "000001");
    var items = data.putArray("items");
    for (int i = 0; i < 180; i++)
      items
          .addObject()
          .put("navDate", LocalDate.of(2026, 1, 1).plusDays(i).toString())
          .put("unitNav", 1 + i / 1000D)
          .put("accumulatedNav", 2 + i / 1000D);
    var state =
        new AgentConversationState("c", "000001", List.of("000001"), null, null, "NAV", now);
    var selected =
        selector.select(
            "它最新的累计净值是多少？",
            state,
            List.of(card("nav", "get_fund_nav_history", "000001", data.toString())),
            3,
            500);
    assertThat(selected.cardIds()).containsExactly("nav");
    assertThat(selected.tokens()).isLessThanOrEqualTo(500);
    assertThat(selected.prompt()).contains("2026-06-29").doesNotContain("2026-03-01");
  }

  /** 同一分区保留最新卡片，过期卡片不出现在提示词里。 过期记忆不能冒充仍有效的事实。 */
  @Test
  void newestValueWinsAndExpiredMemoryCannotLeakIntoThePrompt() {
    var state =
        new AgentConversationState("c", "000001", List.of("000001"), null, null, "PROFILE", now);
    var old =
        new AgentFactCard(
            "old",
            "c",
            "run-1",
            "get_fund_profile",
            "000001",
            List.of(),
            "{\"manager\":\"旧经理\"}",
            now.minusSeconds(600),
            now.plusSeconds(600));
    var current =
        new AgentFactCard(
            "current",
            "c",
            "run-2",
            "get_fund_profile",
            "000001",
            List.of(),
            "{\"manager\":\"新经理\"}",
            now.minusSeconds(60),
            now.plusSeconds(600));
    var expired =
        new AgentFactCard(
            "expired",
            "c",
            "run-3",
            "get_fund_profile",
            "000001",
            List.of(),
            "{\"manager\":\"过期经理\"}",
            now.minusSeconds(1200),
            now.minusSeconds(1));
    var selected = selector.select("它的基金经理是谁？", state, List.of(old, current, expired), 3, 1200);
    assertThat(selected.cardIds()).containsExactly("current");
    assertThat(selected.prompt()).contains("新经理").doesNotContain("旧经理", "过期经理");
  }

  /** 构造一张在当前时间仍然有效的事实卡。 过期与否由调用方传入的时间决定，这里不放宽过滤。 */
  private AgentFactCard card(String id, String tool, String subject, String data) {
    return new AgentFactCard(
        id, "c", "run", tool, subject, List.of(), data, now, now.plusSeconds(3600));
  }
}
