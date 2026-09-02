package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.exception.AgentEvidenceViolationException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FundAgentCitationPolicyTest {
    private final FundAgentCitationPolicy policy=new FundAgentCitationPolicy();
    @Test void appendsPageAwareCitationWhenModelOmittedIt(){var evidence=EvidenceReference.document("DOC:d:v:c","000001","upload","d","v","c","季度报告",LocalDate.of(2026,6,30),7,8,"投资策略","原文","https://example.test");assertThat(policy.validateAndRepair("基金经理强调控制风险。",List.of(evidence))).contains("DOC:d:v:c").contains("第7-8页");}
    @Test void rejectsInventedCitation(){assertThatThrownBy(()->policy.validateAndRepair("依据 DOC:fake:v:c",List.of())).isInstanceOf(AgentEvidenceViolationException.class);}
    @Test void replacesInventedCitationWithCurrentEvidence(){var evidence=new EvidenceReference("ev-fund-1","FUND_PROFILE","000001",null,null,null,"Eastmoney",null,null,null);String answer=policy.validateAndRepair("基金代码为 000001【ev-fake】。",List.of(evidence));assertThat(answer).contains("ev-fund-1").doesNotContain("ev-fake");}
    @Test void acceptsCitationNextToMarkdownPunctuation(){var evidence=new EvidenceReference("ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b","FUND_REALTIME_QUOTE","004069",null,null,null,"eastmoney-push2",null,null,null);String answer=policy.validateAndRepair("| **涨跌幅** | **+0.29%** | ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b|\n",List.of(evidence));assertThat(answer).contains(evidence.evidenceId());}
    @Test void doesNotTreatMarkdownRuleAsPartOfCitation(){var evidence=new EvidenceReference("ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b","FUND_REALTIME_QUOTE","004069",null,null,null,"eastmoney-push2",null,null,null);String answer=policy.validateAndRepair("涨跌幅 +0.29% ev-realtime-50d1d73c-6b89-46f2-a281-9cc39e522c1b---\n",List.of(evidence));assertThat(answer).contains(evidence.evidenceId()+"---");}
    @Test void preservesMarkdownLineBreaksWhileRepairing(){var evidence=new EvidenceReference("ev-sector-1","FUND_SECTOR_OUTLOOK","562500",null,null,null,"tencent-qt",null,null,null);String answer=policy.validateAndRepair("## 情景分析\n\n- 近20日涨跌 -2.01%\n- 基准情景\n",List.of(evidence));assertThat(answer).contains("## 情景分析\n\n- 近20日涨跌").contains("\n- 基准情景\n");}
    @Test void announcementClaimAcceptsVerifiedEventEvidence(){var evidence=new EvidenceReference("ev-event-1","COMPANY_ANNOUNCEMENT","562500",null,null,null,"exchange-announcement",null,null,null);String answer=policy.validateAndRepair("重仓股发布回购公告，构成可核验利好 ev-event-1。",List.of(evidence));assertThat(answer).contains("ev-event-1");}
    @Test void managerIdentityIsNotMisclassifiedAsARequiredDocumentClaim(){var evidence=new EvidenceReference("ev-profile-1","FUND_PROFILE","011612",null,null,null,"eastmoney-public",null,null,null);String answer=policy.validateAndRepair("基金经理为荣膺。",List.of(evidence));assertThat(answer).isEqualTo("基金经理为荣膺。");}
    @Test void mixedHoldingAndAnnouncementClaimAcceptsBothEvidenceTypes(){var holding=new EvidenceReference("ev-holding-1","FUND_HOLDING","562500",null,null,null,"fund-holding",null,null,null);var announcement=new EvidenceReference("ev-event-1","COMPANY_ANNOUNCEMENT","562500",null,null,null,"exchange-announcement",null,null,null,null,null,null,"以集中竞价方式回购公司股份进展公告",null,null,null,null,null,null);String answer=policy.validateAndRepair("中控技术回购是明确利好（6.35%） ev-holding-1。",List.of(holding,announcement));assertThat(answer).contains("ev-holding-1").contains("ev-event-1");}
}
