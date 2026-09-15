package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FundMemorySelectorTest {
    private final Instant now=Instant.parse("2026-09-10T02:00:00Z");
    private final FundMemorySelector selector=new FundMemorySelector(new ObjectMapper());

    @Test void selectsOnlyTheRequestedFundAndCategory() {
        var state=new AgentConversationState("c","000001",List.of("000001","110022"),null,null,"PROFILE",now);
        var selected=selector.select("它的基金经理是谁？",state,List.of(
                card("profile-1","get_fund_profile","000001","{\"manager\":\"张三\"}"),
                card("metrics-1","calculate_fund_metrics","000001","{\"returnRate\":0.1}"),
                card("profile-2","get_fund_profile","110022","{\"manager\":\"李四\"}")),3,1200);
        assertThat(selected.fundCount()).isEqualTo(1);
        assertThat(selected.cardIds()).containsExactly("profile-1");
        assertThat(selected.prompt()).contains("fund=000001","张三").doesNotContain("returnRate","李四");
    }

    @Test void assemblesMultipleRequestedSectionsIntoOneCardPerFund() {
        var state=new AgentConversationState("c","000001",List.of("000001"),null,null,null,now);
        var selected=selector.select("全面分析一下 000001",state,List.of(
                card("profile","get_fund_profile","000001","{\"type\":\"混合型\"}"),
                card("metrics","calculate_fund_metrics","000001","{\"maxDrawdown\":-0.1}")),3,1200);
        assertThat(selected.fundCount()).isEqualTo(1);
        assertThat(selected.cardIds()).containsExactlyInAnyOrder("profile","metrics");
        assertThat(selected.prompt()).contains("profile","metrics");
    }

    @Test void returnsOneAssembledCardForEachComparedFund() {
        var state=new AgentConversationState("c","110022",List.of("000001","110022"),null,null,"METRICS",now);
        var selected=selector.select("这两只基金的最大回撤谁更小？",state,List.of(
                card("a","calculate_fund_metrics","000001","{\"maxDrawdown\":-0.1}"),
                card("b","calculate_fund_metrics","110022","{\"maxDrawdown\":-0.2}")),3,1200);
        assertThat(selected.fundCount()).isEqualTo(2);
        assertThat(selected.prompt()).contains("fund=000001","fund=110022");
    }

    @Test void rejectsMetricsFromADifferentPeriod() {
        var state=new AgentConversationState("c","000001",List.of("000001"),LocalDate.of(2026,1,1),LocalDate.of(2026,8,31),"METRICS",now);
        var evidence=new EvidenceReference("ev","FUND_METRICS","000001",LocalDate.of(2025,1,1),LocalDate.of(2025,12,31),"ACCUMULATED_NAV",null,"v1","a1",now);
        var wrong=new AgentFactCard("wrong","c","r","calculate_fund_metrics","000001",List.of(evidence),"{\"maxDrawdown\":-0.1}",now,now.plusSeconds(60));
        var selected=selector.select("它同期的最大回撤呢？",state,List.of(wrong),3,1200);
        assertThat(selected.cardIds()).isEmpty();
    }

    private AgentFactCard card(String id,String tool,String subject,String data) {
        return new AgentFactCard(id,"c","run",tool,subject,List.of(),data,now,now.plusSeconds(3600));
    }
}
