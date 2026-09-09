package com.jijing.fund.agent.graph;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;

/** A checkpointed catalyst workflow whose native graph cursor controls recovery. */
public final class ResumableCatalystResearchGraph {
    public static final String NAME="catalyst-research";
    public static final String VERSION="v1";
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;
    /** Creates the graph with the evidence-producing and evidence-reviewing roles. */
    public ResumableCatalystResearchGraph(EvidenceResearcher researcher, ResearchReviewer reviewer) { this.researcher=Objects.requireNonNull(researcher,"researcher is required"); this.reviewer=Objects.requireNonNull(reviewer,"reviewer is required"); }
    /** Starts a new graph or lets LangGraph4j resume the saved node and state for this task thread. */
    public Result invoke(Request request, DurableGraphCheckpointSaver saver, String taskId) {
        try {
            RunnableConfig config=RunnableConfig.builder().threadId(taskId).graphId(NAME+":"+VERSION).build();
            var saved=saver.get(config);
            if(saved.filter(value->"COMPLETED".equals(value.getState().get("phase"))).isPresent())return result(saved.orElseThrow().getState());
            Map<String,Object> input=new LinkedHashMap<>();
            input.put("fundCode",request.fundCode()); input.put("theme",request.theme()); input.put("lookbackDays",request.lookbackDays()); input.put("attempt",0); input.put("phase","NEW");
            ResearchState state=graph(saver).invoke(saved.isPresent()?GraphInput.resume():GraphInput.args(input),config).orElseThrow();
            return result(state.data());
        } catch(Exception error) { throw new IllegalStateException("catalyst research graph failed",error); }
    }
    /** Compiles route -> research (one bounded retry) -> review with the framework's durable checkpointer. */
    private CompiledGraph<ResearchState> graph(DurableGraphCheckpointSaver saver) throws Exception {
        StateGraph<ResearchState> graph=new StateGraph<>(ResearchState::new);
        graph.addNode("route",(AsyncNodeAction<ResearchState>) state->CompletableFuture.completedFuture(route(state)));
        graph.addNode("research",(AsyncNodeAction<ResearchState>) state->CompletableFuture.completedFuture(research(state)));
        graph.addNode("review",(AsyncNodeAction<ResearchState>) state->CompletableFuture.completedFuture(review(state)));
        graph.addEdge(GraphDefinition.START,"route");
        graph.addConditionalEdges("route",(AsyncEdgeAction<ResearchState>) state->CompletableFuture.completedFuture("RESEARCH_READY".equals(state.data().get("phase"))?"review":"research"),Map.of("research","research","review","review"));
        graph.addConditionalEdges("research",(AsyncEdgeAction<ResearchState>) state->CompletableFuture.completedFuture(shouldRetry(state)?"retry":"review"),Map.of("retry","research","review","review"));
        graph.addEdge("review",GraphDefinition.END);
        // Preserve final state because the outer task can crash after the graph completes but before task completion commits.
        return graph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).graphId(NAME+":"+VERSION).build());
    }
    /** Leaves routing to the persisted phase while LangGraph4j persists its next-node cursor. */
    private Map<String,Object> route(ResearchState state){return Map.of("phase",String.valueOf(state.data().getOrDefault("phase","NEW")));}
    /** Calls the external researcher from JSON-safe state so JDBC restore does not depend on Java object serialization. */
    private Map<String,Object> research(ResearchState state) { Request request=new Request(string(state,"fundCode"),string(state,"theme"),integer(state,"lookbackDays",45)); int attempt=integer(state,"attempt",0); EvidenceDraft draft=researcher.research(request,attempt); return Map.of("draft",draft.content(),"evidenceIds",draft.evidenceIds(),"retryable",draft.retryable(),"attempt",attempt+1,"phase","RESEARCH_READY"); }
    /** Produces the final cited summary only after at least one verified evidence id exists. */
    private Map<String,Object> review(ResearchState state) { List<String> evidence=evidence(state); if(evidence.isEmpty())throw new IllegalStateException("verified evidence is required before review"); return Map.of("summary",reviewer.review(String.valueOf(state.data().get("draft")),evidence),"phase","COMPLETED"); }
    /** Retains the original single in-graph retry policy after native checkpoint recovery. */
    private boolean shouldRetry(ResearchState state){return Boolean.TRUE.equals(state.data().get("retryable"))&&integer(state,"attempt",0)<2;}
    /** Extracts immutable evidence ids from JSON-safe persisted graph state. */
    private static List<String> evidence(ResearchState state){Object value=state.data().get("evidenceIds");return value instanceof List<?> values?values.stream().map(String::valueOf).toList():List.of();}
    /** Builds a public result from live or completed restored state. */
    private static Result result(Map<String,Object> state){Object value=state.get("evidenceIds");List<String> ids=value instanceof List<?> values?values.stream().map(String::valueOf).toList():List.of();return new Result(String.valueOf(state.get("summary")),ids,integer(state,"attempt",0));}
    /** Reads an optional request scalar without converting null to a literal string. */
    private static String string(ResearchState state,String name){Object value=state.data().get(name);return value==null?null:String.valueOf(value);}
    /** Parses a numeric state value with the graph's server-controlled default. */
    private static int integer(ResearchState state,String name,int fallback){return integer(state.data(),name,fallback);}
    /** Parses a numeric map value after JSON deserialization. */
    private static int integer(Map<String,Object> state,String name,int fallback){Object value=state.get(name);return value==null?fallback:Integer.parseInt(String.valueOf(value));}
    /** Validated graph input. */
    public record Request(String fundCode,String theme,int lookbackDays) implements java.io.Serializable { public Request {if((fundCode==null||fundCode.isBlank())&&(theme==null||theme.isBlank()))throw new IllegalArgumentException("fundCode or theme is required");if(lookbackDays<7||lookbackDays>180)throw new IllegalArgumentException("lookbackDays must be 7..180");} }
    /** Result produced by the evidence role. */
    public record EvidenceDraft(String content,List<String> evidenceIds,boolean retryable) implements java.io.Serializable { public EvidenceDraft {evidenceIds=evidenceIds==null?List.of():List.copyOf(evidenceIds);} }
    /** Final graph result passed to the outer capability executor. */
    public record Result(String summary,List<String> evidenceIds,int attempts) implements java.io.Serializable {}
    /** Evidence-producing role, which may invoke Spring AI tools. */
    @FunctionalInterface public interface EvidenceResearcher { EvidenceDraft research(Request request,int attempt); }
    /** Citation-preserving review role. */
    @FunctionalInterface public interface ResearchReviewer { String review(String draft,List<String> evidenceIds); }
    /** JSON-safe LangGraph4j state wrapper. */
    static final class ResearchState extends AgentState { ResearchState(Map<String,Object> data){super(new LinkedHashMap<>(data));} }
}
