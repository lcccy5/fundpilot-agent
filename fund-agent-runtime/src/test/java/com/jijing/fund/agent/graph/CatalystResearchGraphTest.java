package com.jijing.fund.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CatalystResearchGraphTest {
    @Test void retriesOnlyTheInnerResearchNodeThenSynthesizesTypedState() {
        AtomicInteger calls = new AtomicInteger();
        var graph = new CatalystResearchGraph((request, attempt) -> {
            calls.incrementAndGet();
            return attempt == 0
                    ? new CatalystResearchGraph.EvidenceDraft("temporary source outage", List.of(), true)
                    : new CatalystResearchGraph.EvidenceDraft("verified announcement", List.of("ev-1"), false);
        }, (draft, evidence) -> draft + " / " + evidence.getFirst());

        var result = graph.invoke(new CatalystResearchGraph.Request("000001", null, 45));

        assertThat(calls).hasValue(2);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.summary()).isEqualTo("verified announcement / ev-1");
        assertThat(result.evidenceIds()).containsExactly("ev-1");
    }
}
