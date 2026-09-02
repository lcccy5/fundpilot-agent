package com.jijing.fund.agent.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

/**
 * Inner, in-process graph for a single research capability.  It deliberately
 * does not own run/task/lease state; those durable concerns stay in the outer
 * AgentDagRepository runtime.
 */
public final class CatalystResearchGraph {
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;

    
    /** 执行该 Agent 运行时组件中的 CatalystResearchGraph 操作。 */
    public CatalystResearchGraph(EvidenceResearcher researcher, ResearchReviewer reviewer) {
        this.researcher = Objects.requireNonNull(researcher, "researcher is required");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer is required");
    }

    
    /** 执行 invoke 操作，并应用相应的 Agent 运行时状态变化。 */
    public Result invoke(Request request) {
        try {
            CompiledGraph<ResearchState> graph = graph();
            ResearchState state = graph.invoke(Map.of("request", request, "attempt", 0)).orElseThrow();
            
            /** 执行该 Agent 运行时组件中的 Result 操作。 */
            return new Result(String.valueOf(state.data().get("summary")), evidence(state),
                    Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))));
        } catch (Exception error) {
            
            /** 执行该 Agent 运行时组件中的 IllegalStateException 操作。 */
            throw new IllegalStateException("catalyst research graph failed", error);
        }
    }

    
    /** 执行该 Agent 运行时组件中的 graph 操作。 */
    private CompiledGraph<ResearchState> graph() throws Exception {
        StateGraph<ResearchState> graph = new StateGraph<>(ResearchState::new);
        AsyncNodeAction<ResearchState> research = state -> CompletableFuture.completedFuture(research(state));
        AsyncNodeAction<ResearchState> synthesize = state -> CompletableFuture.completedFuture(synthesize(state));
        AsyncEdgeAction<ResearchState> next = state -> CompletableFuture.completedFuture(shouldRetry(state) ? "retry" : "synthesize");
        graph.addNode("research", research);
        graph.addNode("synthesize", synthesize);
        graph.addEdge(GraphDefinition.START, "research");
        graph.addConditionalEdges("research", next, Map.of("retry", "research", "synthesize", "synthesize"));
        graph.addEdge("synthesize", GraphDefinition.END);
        return graph.compile();
    }

    private Map<String, Object> research(ResearchState state) {
        Request request = (Request) state.data().get("request");
        int attempt = Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0)));
        EvidenceDraft draft = researcher.research(request, attempt);
        return Map.of("draft", draft.content(), "evidenceIds", draft.evidenceIds(), "retryable", draft.retryable(), "attempt", attempt + 1);
    }

    private Map<String, Object> synthesize(ResearchState state) {
        String summary = reviewer.review(String.valueOf(state.data().get("draft")), evidence(state));
        return Map.of("summary", summary);
    }

    
    /** 判断 shouldRetry 对应的条件是否成立。 */
    private boolean shouldRetry(ResearchState state) {
        return Boolean.TRUE.equals(state.data().get("retryable"))
                && Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))) < 2;
    }
    @SuppressWarnings("unchecked")
    
    /** 执行该 Agent 运行时组件中的 evidence 操作。 */
    private static List<String> evidence(ResearchState state) {
        Object value = state.data().get("evidenceIds");
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    
    /** 在 Agent 运行时边界间传递 Request 数据的不可变值对象。 */
    public record Request(String fundCode, String theme, int lookbackDays) implements java.io.Serializable {
        public Request {
            if ((fundCode == null || fundCode.isBlank()) && (theme == null || theme.isBlank())) {
                
                /** 执行该 Agent 运行时组件中的 IllegalArgumentException 操作。 */
                throw new IllegalArgumentException("fundCode or theme is required");
            }
            if (lookbackDays < 7 || lookbackDays > 180) throw new IllegalArgumentException("lookbackDays must be 7..180");
        }
    }
    
    /** 在 Agent 运行时边界间传递 EvidenceDraft 数据的不可变值对象。 */
    public record EvidenceDraft(String content, List<String> evidenceIds, boolean retryable) implements java.io.Serializable {
        public EvidenceDraft { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }
    
    /** 在 Agent 运行时边界间传递 Result 数据的不可变值对象。 */
    public record Result(String summary, List<String> evidenceIds, int attempts) implements java.io.Serializable {}
    @FunctionalInterface 
    /** 定义 EvidenceResearcher 在 Agent 运行时中的能力契约。 */
    public interface EvidenceResearcher { EvidenceDraft research(Request request, int attempt); }
    @FunctionalInterface 
    /** 定义 ResearchReviewer 在 Agent 运行时中的能力契约。 */
    public interface ResearchReviewer { String review(String draft, List<String> evidenceIds); }
    
    /** 实现 ResearchState 所代表的 Agent 运行时职责。 */
    static final class ResearchState extends AgentState { ResearchState(Map<String, Object> data) { super(new LinkedHashMap<>(data)); } }
}
