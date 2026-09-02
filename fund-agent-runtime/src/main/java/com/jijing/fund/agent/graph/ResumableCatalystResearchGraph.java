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
 * Resumable LangGraph4j workflow for one catalyst task. The outer DAG remains
 * responsible for leases, idempotency, cancellation and task completion.
 */
public final class ResumableCatalystResearchGraph {
    public static final String NAME = "catalyst-research";
    public static final String VERSION = "v1";
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;
    private final GraphEventListener events;

    /** Creates a workflow whose completed nodes are observable for durable checkpointing. */
    public ResumableCatalystResearchGraph(EvidenceResearcher researcher, ResearchReviewer reviewer, GraphEventListener events) {
        this.researcher = Objects.requireNonNull(researcher, "researcher is required");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer is required");
        this.events = Objects.requireNonNull(events, "events is required");
    }

    /**
     * Starts from new input or resumes after research, preventing duplicate external observations.
     *
     * @param request validated catalyst input
     * @param resume latest compatible checkpoint state; nullable for a fresh execution
     * @return evidence-backed reviewed result
     */
    public Result invoke(Request request, ResumeState resume) {
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("request", request);
            input.put("attempt", resume == null ? 0 : resume.attempt());
            input.put("phase", resume == null ? "NEW" : resume.phase());
            if (resume != null) {
                input.put("draft", resume.draft());
                input.put("evidenceIds", resume.evidenceIds());
                input.put("retryable", resume.retryable());
            }
            ResearchState state = graph().invoke(input).orElseThrow();
            
            /** 执行该 Agent 运行时组件中的 Result 操作。 */
            return new Result(String.valueOf(state.data().get("summary")), evidence(state),
                    Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))));
        } catch (Exception error) {
            
            /** 执行该 Agent 运行时组件中的 IllegalStateException 操作。 */
            throw new IllegalStateException("catalyst research graph failed", error);
        }
    }

    /** Builds route -> research (one bounded retry) -> review -> end with LangGraph4j conditional edges. */
    private CompiledGraph<ResearchState> graph() throws Exception {
        StateGraph<ResearchState> graph = new StateGraph<>(ResearchState::new);
        graph.addNode("route", (AsyncNodeAction<ResearchState>) state -> CompletableFuture.completedFuture(route(state)));
        graph.addNode("research", (AsyncNodeAction<ResearchState>) state -> CompletableFuture.completedFuture(research(state)));
        graph.addNode("review", (AsyncNodeAction<ResearchState>) state -> CompletableFuture.completedFuture(review(state)));
        graph.addEdge(GraphDefinition.START, "route");
        graph.addConditionalEdges("route", (AsyncEdgeAction<ResearchState>) state -> CompletableFuture.completedFuture(
                "RESEARCH_READY".equals(state.data().get("phase")) ? "review" : "research"), Map.of("research", "research", "review", "review"));
        graph.addConditionalEdges("research", (AsyncEdgeAction<ResearchState>) state -> CompletableFuture.completedFuture(
                shouldRetry(state) ? "retry" : "review"), Map.of("retry", "research", "review", "review"));
        graph.addEdge("review", GraphDefinition.END);
        return graph.compile();
    }

    /** Directs a restored research snapshot to review instead of repeating the external research tool. */
    private Map<String, Object> route(ResearchState state) {
        Map<String, Object> result = Map.of("phase", String.valueOf(state.data().getOrDefault("phase", "NEW")));
        events.nodeCompleted("route", state.data(), result);
        return result;
    }

    /** Executes the evidence role and emits only serializable values suitable for a database checkpoint. */
    private Map<String, Object> research(ResearchState state) {
        Request request = (Request) state.data().get("request");
        int attempt = Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0)));
        EvidenceDraft draft = researcher.research(request, attempt);
        Map<String, Object> result = Map.of("draft", draft.content(), "evidenceIds", draft.evidenceIds(),
                "retryable", draft.retryable(), "attempt", attempt + 1, "phase", "RESEARCH_READY");
        events.nodeCompleted("research", state.data(), result);
        return result;
    }

    /** Requires at least one verified evidence reference before allowing a reviewer to finalize output. */
    private Map<String, Object> review(ResearchState state) {
        List<String> evidence = evidence(state);
        if (evidence.isEmpty()) throw new IllegalStateException("verified evidence is required before review");
        Map<String, Object> result = Map.of("summary", reviewer.review(String.valueOf(state.data().get("draft")), evidence), "phase", "COMPLETED");
        events.nodeCompleted("review", state.data(), result);
        return result;
    }

    /** Limits the inner workflow to one retry; process-level retry is owned by the outer task worker. */
    private boolean shouldRetry(ResearchState state) {
        return Boolean.TRUE.equals(state.data().get("retryable"))
                && Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))) < 2;
    }

    /** Extracts an immutable evidence list from generic LangGraph state. */
    private static List<String> evidence(ResearchState state) {
        Object value = state.data().get("evidenceIds");
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    /** Serializable request data accepted by the graph. */
    public record Request(String fundCode, String theme, int lookbackDays) implements java.io.Serializable {
        public Request {
            if ((fundCode == null || fundCode.isBlank()) && (theme == null || theme.isBlank())) throw new IllegalArgumentException("fundCode or theme is required");
            if (lookbackDays < 7 || lookbackDays > 180) throw new IllegalArgumentException("lookbackDays must be 7..180");
        }
    }
    /** Serializable output of the evidence role, including retry eligibility. */
    public record EvidenceDraft(String content, List<String> evidenceIds, boolean retryable) implements java.io.Serializable {
        public EvidenceDraft { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }
    /** Minimal persisted state that can skip directly to review after a completed research node. */
    public record ResumeState(int attempt, String draft, List<String> evidenceIds, boolean retryable, String phase) implements java.io.Serializable {
        public ResumeState { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); phase = phase == null ? "NEW" : phase; }
    }
    /** Final graph result for the outer capability executor. */
    public record Result(String summary, List<String> evidenceIds, int attempts) implements java.io.Serializable {}
    /** Evidence-producing role; implementation may invoke Spring AI tools. */
    @FunctionalInterface 
    /** 定义 EvidenceResearcher 在 Agent 运行时中的能力契约。 */
    public interface EvidenceResearcher { EvidenceDraft research(Request request, int attempt); }
    /** Reviewer role that turns a cited draft into final text. */
    @FunctionalInterface 
    /** 定义 ResearchReviewer 在 Agent 运行时中的能力契约。 */
    public interface ResearchReviewer { String review(String draft, List<String> evidenceIds); }
    /** Callback called after each node so the outer layer can persist and publish progress. */
    @FunctionalInterface 
    /** 定义 GraphEventListener 在 Agent 运行时中的能力契约。 */
    public interface GraphEventListener {
        
        /** 执行该 Agent 运行时组件中的 nodeCompleted 操作。 */
        void nodeCompleted(String nodeName, Map<String, Object> previousState, Map<String, Object> update);
    }
    /** LangGraph4j state wrapper containing only serializable values. */
    static final class ResearchState extends AgentState { ResearchState(Map<String, Object> data) { super(new LinkedHashMap<>(data)); } }
}
