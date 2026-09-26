package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 确认引用缺失时会补上类型匹配的证据，编造的引用不能留下来。 没有任何证据却写出引用时回答被拒绝。 */
class FundAgentCitationPolicyTest {
  private final FundAgentCitationPolicy policy = new FundAgentCitationPolicy();

  /** 模型漏掉文档引用时，用带页码的文档证据补上。 补不上时该回答不能通过。 */
  @Test
  void appendsPageAwareCitationWhenModelOmittedIt() {
    var evidence =
        EvidenceReference.document(
            "DOC:d:v:c",
            "000001",
            "upload",
            "d",
            "v",
            "c",
            "季度报告",
            LocalDate.of(2026, 6, 30),
            7,
            8,
            "投资策略",
            "原文",
            "https://example.test");
    assertThat(policy.validateAndRepair("基金经理强调控制风险。", List.of(evidence)))
        .contains("DOC:d:v:c")
        .contains("第7-8页");
  }

  /** 本轮没有证据却出现引用时直接拒绝。 不会把编造的文档编号改写成空引用后放行。 */
  @Test
  void rejectsInventedCitation() {
    assertThatThrownBy(() -> policy.validateAndRepair("依据 DOC:fake:v:c", List.of()))
        .isInstanceOf(AgentEvidenceViolationException.class);
  }

  /** 不存在的证据编号会被去掉，并补上本次真实证据。 数字事实不能继续带着假引用返回。 */
  @Test
  void replacesInventedCitationWithCurrentEvidence() {
    var evidence =
        new EvidenceReference(
            "ev-fund-1", "FUND_PROFILE", "000001", null, null, null, "Eastmoney", null, null, null);
    String answer = policy.validateAndRepair("基金代码为 000001【ev-fake】。", List.of(evidence));
    assertThat(answer).contains("ev-fund-1").doesNotContain("ev-fake");
  }

  /** 紧挨表格竖线的引用仍然算本次证据。 排版符号不会让合法引用被当成编造。 */
  @Test
  void acceptsCitationNextToMarkdownPunctuation() {
    var evidence =
        new EvidenceReference(
            "ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b",
            "FUND_REALTIME_QUOTE",
            "004069",
            null,
            null,
            null,
            "eastmoney-push2",
            null,
            null,
            null);
    String answer =
        policy.validateAndRepair(
            "| **涨跌幅** | **+0.29%** | ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b|\n",
            List.of(evidence));
    assertThat(answer).contains(evidence.evidenceId());
  }

  /** 引用后面的 Markdown 分隔线不属于证据编号。 分隔线必须留在原文里，不能被引用清洗吃掉。 */
  @Test
  void doesNotTreatMarkdownRuleAsPartOfCitation() {
    var evidence =
        new EvidenceReference(
            "ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b",
            "FUND_REALTIME_QUOTE",
            "004069",
            null,
            null,
            null,
            "eastmoney-push2",
            null,
            null,
            null);
    String answer =
        policy.validateAndRepair(
            "涨跌幅 +0.29% ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b---\n", List.of(evidence));
    assertThat(answer).contains(evidence.evidenceId() + "---");
  }

  /** 补引用时保留 Markdown 换行。 修复不得把多行情景分析压成一行。 */
  @Test
  void preservesMarkdownLineBreaksWhileRepairing() {
    var evidence =
        new EvidenceReference(
            "ev-sector-1",
            "FUND_SECTOR_OUTLOOK",
            "562500",
            null,
            null,
            null,
            "tencent-qt",
            null,
            null,
            null);
    String answer =
        policy.validateAndRepair("## 情景分析\n\n- 近20日涨跌 -2.01%\n- 基准情景\n", List.of(evidence));
    assertThat(answer).contains("## 情景分析\n\n- 近20日涨跌").contains("\n- 基准情景\n");
  }

  /** 公告类声明接受已核验的公司公告证据。 不会因为句子里同时有利好字样而改要另一类证据。 */
  @Test
  void announcementClaimAcceptsVerifiedEventEvidence() {
    var evidence =
        new EvidenceReference(
            "ev-event-1",
            "COMPANY_ANNOUNCEMENT",
            "562500",
            null,
            null,
            null,
            "exchange-announcement",
            null,
            null,
            null);
    String answer = policy.validateAndRepair("重仓股发布回购公告，构成可核验利好 ev-event-1。", List.of(evidence));
    assertThat(answer).contains("ev-event-1");
  }

  /** 只陈述经理姓名时不把它当成必须引用文档的声明。 基金概况证据存在时原文保持不变，不会误补文档引用。 */
  @Test
  void managerIdentityIsNotMisclassifiedAsARequiredDocumentClaim() {
    var evidence =
        new EvidenceReference(
            "ev-profile-1",
            "FUND_PROFILE",
            "011612",
            null,
            null,
            null,
            "eastmoney-public",
            null,
            null,
            null);
    String answer = policy.validateAndRepair("基金经理为荣膺。", List.of(evidence));
    assertThat(answer).isEqualTo("基金经理为荣膺。");
  }

  /** 同时涉及持仓比例和公告事项时，两类证据都要留下。 只有持仓证据不足以支撑公告声明。 */
  @Test
  void mixedHoldingAndAnnouncementClaimAcceptsBothEvidenceTypes() {
    var holding =
        new EvidenceReference(
            "ev-holding-1",
            "FUND_HOLDING",
            "562500",
            null,
            null,
            null,
            "fund-holding",
            null,
            null,
            null);
    var announcement =
        new EvidenceReference(
            "ev-event-1",
            "COMPANY_ANNOUNCEMENT",
            "562500",
            null,
            null,
            null,
            "exchange-announcement",
            null,
            null,
            null,
            null,
            null,
            null,
            "以集中竞价方式回购公司股份进展公告",
            null,
            null,
            null,
            null,
            null,
            null);
    String answer =
        policy.validateAndRepair(
            "中控技术回购是明确利好（6.35%） ev-holding-1。", List.of(holding, announcement));
    assertThat(answer).contains("ev-holding-1").contains("ev-event-1");
  }
}
