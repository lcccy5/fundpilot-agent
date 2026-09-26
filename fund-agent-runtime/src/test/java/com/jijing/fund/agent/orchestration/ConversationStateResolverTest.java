package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.jijing.fund.agent.api.AgentConversationState;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 确认会话笔记能记住基金、区间和话题，非法或相对时间按既定规则取舍。 笔记更新失败不会被当成计划或路由失败。 */
class ConversationStateResolverTest {
  /** 一句话里的多只基金、显式日期和收益话题写入笔记。 活跃基金取最后出现的代码。 */
  @Test
  void recordsMultipleFundsPeriodAndActiveTopic() {
    Instant now = Instant.parse("2026-09-10T02:00:00Z");
    var state =
        new ConversationStateResolver()
            .update(
                AgentConversationState.empty("conversation"),
                "比较 000001 和 110022 在2026年1月1日至2026年8月31日的最大回撤",
                now);
    assertThat(state.mentionedFunds()).containsExactly("000001", "110022");
    assertThat(state.activeFund()).isEqualTo("110022");
    assertThat(state.periodStart()).isEqualTo(LocalDate.of(2026, 1, 1));
    assertThat(state.periodEnd()).isEqualTo(LocalDate.of(2026, 8, 31));
    assertThat(state.activeTopic()).isEqualTo("METRICS");
  }

  /** 只含指代的追问沿用上一轮基金、区间和话题。 解析不到新日期时不把区间清空。 */
  @Test
  void preservesFundAndPeriodForAReferenceOnlyFollowUp() {
    Instant before = Instant.parse("2026-09-10T02:00:00Z");
    var previous =
        new AgentConversationState(
            "conversation",
            "000001",
            java.util.List.of("000001"),
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 8, 31),
            "METRICS",
            before);
    var state =
        new ConversationStateResolver().update(previous, "它同期的收益呢？", before.plusSeconds(30));
    assertThat(state.activeFund()).isEqualTo("000001");
    assertThat(state.periodStart()).isEqualTo(previous.periodStart());
    assertThat(state.activeTopic()).isEqualTo("METRICS");
  }

  /** 相对区间按上海时区计算。 无法识别的相对说法不会改写已有区间。 */
  @Test
  void resolvesRelativePeriodInTheBusinessTimezone() {
    Instant now = Instant.parse("2026-09-18T16:30:00Z");
    var state =
        new ConversationStateResolver()
            .update(AgentConversationState.empty("conversation"), "看看000001近三个月的收益表现", now);
    assertThat(state.periodStart()).isEqualTo(LocalDate.of(2026, 6, 19));
    assertThat(state.periodEnd()).isEqualTo(LocalDate.of(2026, 9, 19));
    assertThat(state.activeTopic()).isEqualTo("METRICS");
  }

  /** 原文同时有相对说法和两个显式日期时，显式日期获胜。 相对区间因此不会覆盖用户指定的范围。 */
  @Test
  void explicitDatesTakePrecedenceOverRelativeWords() {
    Instant now = Instant.parse("2026-09-19T02:00:00Z");
    var state =
        new ConversationStateResolver()
            .update(
                AgentConversationState.empty("conversation"),
                "虽然是近一年，但请按2025年1月1日至2025年12月31日计算",
                now);
    assertThat(state.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
    assertThat(state.periodEnd()).isEqualTo(LocalDate.of(2025, 12, 31));
  }
}
