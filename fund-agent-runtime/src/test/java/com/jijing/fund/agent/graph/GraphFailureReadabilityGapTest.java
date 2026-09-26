package com.jijing.fund.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.capability.CapabilityExecutionContext;
import com.jijing.fund.agent.port.AgentDagRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.Test;

class GraphFailureReadabilityGapTest {
    @Test
    void checkpointStoreRejectsDuplicateSequenceMissingReplaceAndForeignVersions() {
        var store = new InMemoryGraphCheckpointStore();
        var first = checkpoint("cp-1", 1, "v1", Map.of("phase", "NEW"));
        store.append(first);
        assertThatThrownBy(() -> store.append(checkpoint("cp-2", 1, "v1", Map.of("phase", "NEW"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("duplicate graph checkpoint sequence");
        assertThatThrownBy(() -> store.replace(checkpoint("missing", 2, "v1", Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("checkpoint does not exist");
        store.append(checkpoint("cp-other", 2, "v2", Map.of("phase", "NEW")));
        assertThat(store.findAll("run-1", "task-1", "catalyst-research", "v1")).extracting(GraphCheckpoint::checkpointId)
                .containsExactly("cp-1");
        assertThat(store.findLatest("run-1", "missing", "catalyst-research", "v1")).isEmpty();

        assertThatThrownBy(() -> checkpoint(" ", 1, "v1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("checkpointId, runId and taskId are required");
        Map<String, Object> nullValue = new HashMap<>();
        nullValue.put("phase", null);
        assertThatThrownBy(() -> checkpoint("cp-3", 3, "v1", nullValue)).isInstanceOf(NullPointerException.class);
        assertThat(checkpoint("cp-3", 3, "v1", null).state()).isEmpty();
    }

    @Test
    void inProcessGraphStopsOnInvalidInputResearchFailureAndMissingCitation() {
        assertThatThrownBy(() -> new CatalystResearchGraph.Request(" ", " ", 45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fundCode or theme is required");
        assertThatThrownBy(() -> new CatalystResearchGraph.Request("000001", null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("lookbackDays must be 7..180");
        assertThat(new CatalystResearchGraph.EvidenceDraft("draft", null, false).evidenceIds()).isEmpty();

        var broken = new CatalystResearchGraph((request, attempt) -> {
            throw new IllegalStateException("source down");
        }, (draft, evidence) -> draft);
        assertThatThrownBy(() -> broken.invoke(new CatalystResearchGraph.Request("000001", null, 45)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalyst research graph failed")
                .cause()
                .hasMessage("source down");

        AtomicInteger attempts = new AtomicInteger();
        var missingCitation = new CatalystResearchGraph((request, attempt) -> {
            attempts.incrementAndGet();
            return new CatalystResearchGraph.EvidenceDraft("uncited", List.of(), true);
        }, (draft, evidence) -> {
            if (evidence.isEmpty()) {
                throw new IllegalStateException("verified evidence is required before review");
            }
            return draft;
        });
        assertThatThrownBy(() -> missingCitation.invoke(new CatalystResearchGraph.Request("000001", null, 45)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("catalyst research graph failed")
                .cause()
                .hasMessage("verified evidence is required before review");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void resumableGraphsRejectMissingEvidenceAndReturnCompletedCheckpointsWithoutRerun() {
        var decline = new ResumableDeclineAttributionGraph((request, attempt) ->
                new ResumableDeclineAttributionGraph.EvidenceDraft("uncited", List.of(), false),
                (draft, evidence) -> draft);
        assertThatThrownBy(() -> decline.invoke(new ResumableDeclineAttributionGraph.Request("000001", null, 45),
                saver(ResumableDeclineAttributionGraph.NAME, ResumableDeclineAttributionGraph.VERSION, new InMemoryGraphCheckpointStore(), true),
                "task-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("decline attribution graph failed");

        var store = new InMemoryGraphCheckpointStore();
        store.append(new GraphCheckpoint("cp-done", "run-1", "task-1", ResumableCatalystResearchGraph.NAME,
                ResumableCatalystResearchGraph.VERSION, 1, "review", "END",
                Map.of("phase", "COMPLETED", "summary", "cited", "evidenceIds", List.of("ev-9"), "attempt", 1),
                Instant.parse("2026-09-26T00:00:00Z")));
        var catalyst = new ResumableCatalystResearchGraph((request, attempt) -> {
            throw new IllegalStateException("should not research a completed graph");
        }, (draft, evidence) -> draft);
        var result = catalyst.invoke(new ResumableCatalystResearchGraph.Request("000001", null, 45),
                saver(ResumableCatalystResearchGraph.NAME, ResumableCatalystResearchGraph.VERSION, store, true), "task-1");
        assertThat(result.summary()).isEqualTo("cited");
        assertThat(result.evidenceIds()).containsExactly("ev-9");
        assertThat(result.attempts()).isEqualTo(1);
    }

    @Test
    void durableSaverSurfacesLeaseLossSerializationFailureAndReplacesSameCheckpoint() throws Exception {
        var store = new InMemoryGraphCheckpointStore();
        var lostLease = saver("catalyst-research", "v1", store, false);
        var checkpoint = Checkpoint.builder().id("cp-1").nodeId("research").nextNodeId("review").state(Map.of("phase", "NEW")).build();
        assertThatThrownBy(() -> lostLease.put(RunnableConfig.builder().build(), checkpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("lease lost");
        assertThat(store.findLatest("run-1", "task-1", "catalyst-research", "v1")).isEmpty();
        assertThat(lostLease.threadId(RunnableConfig.builder().build())).isEqualTo("task-1");
        assertThat(lostLease.threadId(RunnableConfig.builder().threadId("worker-thread").build())).isEqualTo("worker-thread");

        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        var failingEvent = new DurableGraphCheckpointSaver("run-1", "task-1", "catalyst-research", "v1", store,
                context(true), mock(AgentDagRepository.class), mapper);
        assertThatThrownBy(() -> failingEvent.put(RunnableConfig.builder().threadId("task-1").build(), checkpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot serialize graph checkpoint event");
        assertThat(store.findLatest("run-1", "task-1", "catalyst-research", "v1")).isPresent();

        var healthy = saver("catalyst-research", "v1", store, true);
        var replaced = Checkpoint.builder().id("cp-1").nodeId("research").nextNodeId("review").state(Map.of("phase", "RESEARCH_READY")).build();
        healthy.put(RunnableConfig.builder().threadId("task-1").build(), replaced);
        assertThat(store.findAll("run-1", "task-1", "catalyst-research", "v1")).hasSize(1);
        assertThat(store.findLatest("run-1", "task-1", "catalyst-research", "v1").orElseThrow().state())
                .containsEntry("phase", "RESEARCH_READY");
        var second = Checkpoint.builder().id("cp-2").nodeId("review").nextNodeId("END").state(Map.of("phase", "COMPLETED")).build();
        healthy.put(RunnableConfig.builder().threadId("task-1").build(), second);
        assertThat(healthy.get(RunnableConfig.builder().checkPointId("cp-1").build()).orElseThrow().getId()).isEqualTo("cp-1");
        assertThat(healthy.get(RunnableConfig.builder().checkPointId("missing").build()).orElseThrow().getId()).isEqualTo("cp-2");
        assertThat(healthy.release(RunnableConfig.builder().build()).checkpoints()).hasSize(2);
    }

    private GraphCheckpoint checkpoint(String id, long sequence, String version, Map<String, Object> state) {
        return new GraphCheckpoint(id, "run-1", "task-1", "catalyst-research", version, sequence, "research", "review", state,
                Instant.parse("2026-09-26T00:00:00Z"));
    }

    private DurableGraphCheckpointSaver saver(String graphName, String version, InMemoryGraphCheckpointStore store, boolean leaseAllowsWrites) {
        return new DurableGraphCheckpointSaver("run-1", "task-1", graphName, version, store, context(leaseAllowsWrites),
                mock(AgentDagRepository.class), new ObjectMapper());
    }

    private CapabilityExecutionContext context(boolean leaseAllowsWrites) {
        var task = new AgentDagRepository.ClaimedTask("task-1", "run-1", "plan-1", 1, "research", "CATALYST_RESEARCH", "{}", "hash", 1, "owner");
        if (leaseAllowsWrites) {
            return new CapabilityExecutionContext(task, () -> {}, Runnable::run);
        }
        return new CapabilityExecutionContext(task, () -> {}, writes -> {
            throw new IllegalStateException("lease lost");
        });
    }
}
