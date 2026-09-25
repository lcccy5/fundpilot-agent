package com.jijing.fund.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ResearchHistoryTest {
    @Test void historyKeepsTheQuestionAndFundForTheOwnerOnly() {
        var dag = new InMemoryAgentDagRepository();
        Instant earlier = Instant.parse("2026-09-25T01:00:00Z");
        Instant later = Instant.parse("2026-09-25T02:00:00Z");
        String mine = dag.startRun("c", "user-a", "r", "PLAN_AND_EXECUTE", "rule", earlier);
        dag.rememberResearch(mine, "user-a", "比较000001近一年的回撤", "000001");
        String other = dag.startRun("c2", "user-b", "r", "PLAN_AND_EXECUTE", "rule", later);
        dag.rememberResearch(other, "user-b", "别人的问题", null);
        String newer = dag.startRun("c3", "user-a", "r", "PLAN_AND_EXECUTE", "rule", later);
        dag.rememberResearch(newer, "user-a", "再看110022", "110022");
        var history = dag.listOwnedResearch("user-a", 10);
        assertThat(history).extracting(item -> item.fundCode()).containsExactly("110022", "000001");
        assertThat(history.get(1).message()).contains("000001");
        assertThat(history).noneMatch(item -> "别人的问题".equals(item.message()));
    }
}
