package com.jijing.fund.agent.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.junit.jupiter.api.Test;

/**
 * V6 iteration-0 guard: use the core API directly so the project can keep its
 * current Spring AI version instead of inheriting an adapter's dependency line.
 */
class LangGraph4jCompatibilityTest {
    @Test
    void coreGraphExecutesWithoutSpringAiAdapter() throws Exception {
        StateGraph<ResearchState> graph = new StateGraph<>(ResearchState::new);
        AsyncNodeAction<ResearchState> research = state -> CompletableFuture.completedFuture(Map.of(
                "subject", state.data().getOrDefault("subject", "unknown"),
                "evidence", "ev-spike"));
        graph.addNode("research", research);
        graph.addEdge(GraphDefinition.START, "research");
        graph.addEdge("research", GraphDefinition.END);

        CompiledGraph<ResearchState> compiled = graph.compile();
        ResearchState result = compiled.invoke(Map.of("subject", "000001")).orElseThrow();

        assertThat(result.data().get("subject")).isEqualTo("000001");
        assertThat(result.data().get("evidence")).isEqualTo("ev-spike");
    }

    static final class ResearchState extends AgentState {
        ResearchState(Map<String, Object> data) {
            super(data);
        }
    }
}
