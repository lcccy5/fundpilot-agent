package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 用固定样本检查记忆检索的意图、冲突、弃权和提示词成本。 选错卡、该弃权却命中，或超出预算时评估失败。 */
class MemoryRetrievalQualityEvaluationTest {
  private static final Instant NOW = Instant.parse("2026-09-19T02:00:00Z");
  private static final int TOKEN_BUDGET = 500;
  private final ObjectMapper mapper = new ObjectMapper();
  private final FundMemorySelector selector = new FundMemorySelector(mapper);

  /** 五类样本的召回、精确率、弃权和预算都必须达到百分之百。 任一失败都说明检索把错误或过期事实送进了提示词。 */
  @Test
  void evaluatesRetrievalQualityAndCostTogether() throws Exception {
    List<Case> cases = cases();
    List<Observation> observations = new ArrayList<>();
    int expected = 0, selected = 0, matched = 0, abstentionCases = 0, correctAbstentions = 0;
    for (Case item : cases) {
      var result =
          selector.select(item.question(), item.state(), item.candidates(), 3, TOKEN_BUDGET);
      Set<String> actual = new LinkedHashSet<>(result.cardIds());
      Set<String> hits = new LinkedHashSet<>(actual);
      hits.retainAll(item.expectedCardIds());
      expected += item.expectedCardIds().size();
      selected += actual.size();
      matched += hits.size();
      if (item.expectedCardIds().isEmpty()) {
        abstentionCases++;
        if (actual.isEmpty()) correctAbstentions++;
      }
      observations.add(
          new Observation(
              item.id(),
              item.expectedCardIds(),
              actual,
              result.tokens(),
              result.tokens() <= TOKEN_BUDGET));
    }
    double recall = percent(matched, expected);
    double precision = selected == 0 ? 100 : percent(matched, selected);
    double abstentionAccuracy = percent(correctAbstentions, abstentionCases);
    double budgetCompliance =
        percent(observations.stream().filter(Observation::withinBudget).count(), cases.size());
    Report report =
        new Report(
            "fund-memory-retrieval-v2",
            cases.size(),
            recall,
            precision,
            abstentionAccuracy,
            budgetCompliance,
            List.copyOf(observations),
            "Deterministic fixture evaluation; no live model. Measures retrieval, conflict"
                + " handling, abstention and prompt budget, not answer quality.");
    Path target = Path.of("target", "memory-retrieval-quality-report.json");
    Files.createDirectories(target.getParent());
    mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
    System.out.printf(
        Locale.ROOT,
        "MEMORY_RETRIEVAL cases=%d recall=%.2f%% precision=%.2f%% abstention=%.2f%%"
            + " budget=%.2f%%%n",
        cases.size(),
        recall,
        precision,
        abstentionAccuracy,
        budgetCompliance);
    assertThat(recall).isEqualTo(100);
    assertThat(precision).isEqualTo(100);
    assertThat(abstentionAccuracy).isEqualTo(100);
    assertThat(budgetCompliance).isEqualTo(100);
  }

  /** 组装意图、多主体、区间冲突、无关弃权和大净值五类样本。 区间冲突和无关问题的期望结果是不选卡。 */
  private List<Case> cases() throws Exception {
    var profileState = state("000001", null, null, "PROFILE");
    var metricState =
        state("110022", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31), "METRICS");
    var navState = state("000001", null, null, "NAV");
    AgentFactCard oldProfile =
        card(
            "profile-old",
            "get_fund_profile",
            "000001",
            "{\"manager\":\"旧经理\"}",
            NOW.minusSeconds(300));
    AgentFactCard newProfile =
        card(
            "profile-new",
            "get_fund_profile",
            "000001",
            "{\"manager\":\"新经理\"}",
            NOW.minusSeconds(30));
    AgentFactCard otherProfile =
        card(
            "profile-other",
            "get_fund_profile",
            "110022",
            "{\"manager\":\"其他经理\"}",
            NOW.minusSeconds(10));
    AgentFactCard metricsA =
        metricCard("metrics-a", "000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31));
    AgentFactCard metricsB =
        metricCard("metrics-b", "110022", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 31));
    AgentFactCard wrongPeriod =
        metricCard(
            "metrics-wrong-period", "110022", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
    AgentFactCard nav =
        card("nav-large", "get_fund_nav_history", "000001", largeNavJson(), NOW.minusSeconds(20));
    List<AgentFactCard> all =
        List.of(oldProfile, newProfile, otherProfile, metricsA, metricsB, wrongPeriod, nav);
    return List.of(
        new Case("intent-and-entity", "它的基金经理是谁？", profileState, all, Set.of("profile-new")),
        new Case(
            "multi-entity",
            "比较000001和110022同期的最大回撤",
            metricState,
            all,
            Set.of("metrics-a", "metrics-b")),
        new Case("period-conflict", "它同期的最大回撤是多少？", metricState, List.of(wrongPeriod), Set.of()),
        new Case("unrelated-abstention", "你好，介绍一下你自己", profileState, all, Set.of()),
        new Case("large-value-projection", "它最新的累计净值是多少？", navState, all, Set.of("nav-large")));
  }

  /** 构造带活跃基金、区间和话题的笔记。 未知话题不会在这里被改写成可检索分区。 */
  private AgentConversationState state(
      String active, LocalDate start, LocalDate end, String topic) {
    return new AgentConversationState(
        "c", active, List.of("000001", "110022"), start, end, topic, NOW);
  }

  /** 构造带区间证据的指标卡。 区间与笔记相差过大时选择器应排除它。 */
  private AgentFactCard metricCard(String id, String fund, LocalDate start, LocalDate end) {
    var evidence =
        new EvidenceReference(
            "ev-" + id,
            "FUND_METRICS",
            fund,
            start,
            end,
            "ACCUMULATED_NAV",
            null,
            "v1",
            "metrics-v1",
            NOW);
    return new AgentFactCard(
        id,
        "c",
        "run",
        "calculate_fund_metrics",
        fund,
        List.of(evidence),
        "{\"maxDrawdown\":-0.12,\"annualizedVolatility\":0.18}",
        NOW.minusSeconds(30),
        NOW.plusSeconds(3600));
  }

  /** 构造指定工具和创建时间的事实卡。 过期时间晚于样本时刻，是否入选取决于分区和主体。 */
  private AgentFactCard card(String id, String tool, String fund, String data, Instant createdAt) {
    return new AgentFactCard(
        id, "c", "run", tool, fund, List.of(), data, createdAt, NOW.plusSeconds(3600));
  }

  /** 生成一百八十天的净值 JSON。 投影必须把它压进预算，而不是让评估因超长失败。 */
  private String largeNavJson() throws Exception {
    var root = mapper.createObjectNode().put("fundCode", "000001");
    var items = root.putArray("items");
    for (int i = 0; i < 180; i++)
      items
          .addObject()
          .put("navDate", LocalDate.of(2026, 1, 1).plusDays(i).toString())
          .put("unitNav", 1 + i / 1000D)
          .put("accumulatedNav", 2 + i / 1000D);
    return mapper.writeValueAsString(root);
  }

  /** 计算百分比；分母为零时记为百分之百，表示没有可失败的样本。 该约定只用于评估汇总。 */
  private static double percent(long numerator, long denominator) {
    return denominator == 0 ? 100 : 100D * numerator / denominator;
  }

  /** 一条检索样本及其期望卡片。 期望为空表示应该弃权，选中任何卡都是失败。 */
  private record Case(
      String id,
      String question,
      AgentConversationState state,
      List<AgentFactCard> candidates,
      Set<String> expectedCardIds) {}

  /** 一条样本的期望、实际选择和提示词开销。 超出预算时 withinBudget 为 false，整次评估失败。 */
  private record Observation(
      String caseId,
      Set<String> expectedCardIds,
      Set<String> selectedCardIds,
      int promptTokens,
      boolean withinBudget) {}

  /** 一次检索评估的汇总。 召回或精确率下降表示失败路径被当成了命中。 */
  private record Report(
      String datasetVersion,
      int cases,
      double recallPercent,
      double precisionPercent,
      double abstentionAccuracyPercent,
      double budgetCompliancePercent,
      List<Observation> observations,
      String scope) {}
}
