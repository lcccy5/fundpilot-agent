package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import com.jijing.fund.agent.graph.GraphCheckpoint;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import com.jijing.fund.agent.graph.ResumableCatalystResearchGraph;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.tool.FundCatalystResearchTool;
import com.jijing.fund.agent.tool.ToolResultStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Bridges the durable plan task to a LangGraph4j catalyst subgraph. It stores
 * every post-node snapshot and maps tool/node progress back onto the existing
 * run event stream, while the outer worker retains leases and idempotency.
 */
public final class CatalystResearchCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundCatalystResearchTool tool;
    private final AgentDagRepository dag;
    private final GraphCheckpointStore checkpoints;
    private final ObjectMapper mapper;

    /** Creates the executor using the existing Spring AI tool as the evidence-producing role. */
    public CatalystResearchCapabilityExecutor(FundCatalystResearchTool tool, AgentDagRepository dag,
                                              GraphCheckpointStore checkpoints, ObjectMapper mapper) {
        this.tool = tool;
        this.dag = dag;
        this.checkpoints = checkpoints;
        this.mapper = mapper;
    }

    /** Returns the allow-listed capability resolved by the worker registry. */
    @Override 
    /** 执行该 Agent 运行时组件中的 capabilityType 操作。 */
    public String capabilityType() { return "CATALYST_RESEARCH"; }

    /**
     * Runs or resumes a catalyst graph from the latest compatible checkpoint.
     * Tool audit records are translated to graph-scoped run events instead of
     * writing to the incompatible direct-chat run repository.
     */
    @Override 
    /** 执行 execute 操作，并应用相应的 Agent 运行时状态变化。 */
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        var task = context.task();
        Map<String, Object> input = CapabilityJson.input(mapper, task.inputJson());
        var request = new ResumableCatalystResearchGraph.Request(optional(input, "fundCode"), optional(input, "theme"), integer(input, "lookbackDays", 45));
        Optional<GraphCheckpoint> latest = checkpoints.findLatest(task.runId(), task.taskId(), ResumableCatalystResearchGraph.NAME, ResumableCatalystResearchGraph.VERSION);
        var resume = latest.flatMap(this::resume).orElse(null);
        AtomicLong sequence = new AtomicLong(latest.map(GraphCheckpoint::sequence).orElse(0L));
        dag.appendEvent(task.runId(), "graph.started", json(Map.of("taskKey", task.taskKey(), "graph", ResumableCatalystResearchGraph.NAME, "resumed", resume != null)), Instant.now());

        AgentExecutionTrace trace = new AgentExecutionTrace(task.runId(), new TaskTraceRepository(dag, mapper), mapper, 8, 2, Duration.ofSeconds(15));
        var graph = new ResumableCatalystResearchGraph(
                (graphRequest, attempt) -> research(graphRequest, attempt, trace),
                (draft, evidence) -> review(draft, evidence),
                (node, previous, update) -> checkpoint(task, sequence.incrementAndGet(), node, previous, update));
        var result = graph.invoke(request, resume);
        Map<String, Object> artifact = Map.of("summary", result.summary(), "evidenceIds", result.evidenceIds(), "attempts", result.attempts(), "graph", ResumableCatalystResearchGraph.NAME, "graphVersion", ResumableCatalystResearchGraph.VERSION);
        dag.appendEvent(task.runId(), "graph.completed", json(Map.of("taskKey", task.taskKey(), "evidenceCount", result.evidenceIds().size(), "attempts", result.attempts())), Instant.now());
        return new CapabilityExecutionResult(CapabilityJson.artifactUri(mapper, context, artifact), result.evidenceIds());
    }

    /** Calls the existing Spring AI tool with a server-created trace and converts its envelope to graph state. */
    private ResumableCatalystResearchGraph.EvidenceDraft research(ResumableCatalystResearchGraph.Request request, int attempt, AgentExecutionTrace trace) {
        var envelope = tool.research(new FundCatalystResearchTool.Input(blankToNull(request.theme()), blankToNull(request.fundCode()), 10, request.lookbackDays()),
                new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace)));
        List<String> evidence = envelope.evidence().stream().map(reference -> reference.evidenceId()).toList();
        String draft = envelope.data() == null ? envelope.safeErrorMessage() : json(envelope.data());
        // Only a transient upstream state is retried inside the graph; invalid user input fails immediately.
        boolean retryable = envelope.status() == ToolResultStatus.DATA_NOT_READY && attempt == 0;
        if (envelope.status() == ToolResultStatus.USER_CORRECTABLE) throw new IllegalArgumentException(envelope.safeErrorMessage());
        return new ResumableCatalystResearchGraph.EvidenceDraft(draft, evidence, retryable);
    }

    /** Builds a bounded, citation-preserving reviewer output without introducing an ungrounded second tool call. */
    private String review(String draft, List<String> evidenceIds) {
        return "催化剂研究已完成；以下结论仅基于已验证证据 " + String.join(",", evidenceIds) + "。\n" + draft;
    }

    /** Persists merged post-node state before publishing the matching event, so recovery never claims unpersisted progress. */
    private void checkpoint(com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask task, long sequence, String node,
                            Map<String, Object> previous, Map<String, Object> update) {
        Map<String, Object> state = new LinkedHashMap<>(previous);
        state.putAll(update);
        String phase = String.valueOf(state.getOrDefault("phase", "NEW"));
        checkpoints.append(new GraphCheckpoint(task.runId(), task.taskId(), ResumableCatalystResearchGraph.NAME,
                ResumableCatalystResearchGraph.VERSION, sequence, node, phase, state, Instant.now()));
        dag.appendEvent(task.runId(), "graph.node.completed", json(Map.of("taskKey", task.taskKey(), "node", node, "phase", phase, "sequence", sequence)), Instant.now());
    }

    /** Converts a persisted generic state map into the minimal resume contract, ignoring incompatible phases. */
    private Optional<ResumableCatalystResearchGraph.ResumeState> resume(GraphCheckpoint checkpoint) {
        if (!"RESEARCH_READY".equals(checkpoint.phase())) return Optional.empty();
        Map<String, Object> state = checkpoint.state();
        Object evidence = state.get("evidenceIds");
        List<String> evidenceIds = evidence instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        return Optional.of(new ResumableCatalystResearchGraph.ResumeState(integer(state, "attempt", 0), String.valueOf(state.getOrDefault("draft", "")), evidenceIds,
                Boolean.TRUE.equals(state.get("retryable")), checkpoint.phase()));
    }

    /** Reads an optional string without allowing the model to substitute a missing value with the literal "null". */
    private static String optional(Map<String, Object> input, String name) {
        Object value = input.get(name);
        return value == null ? null : blankToNull(String.valueOf(value));
    }
    /** Parses bounded integer settings from task JSON, falling back to a server-approved default. */
    private static int integer(Map<String, Object> input, String name, int fallback) {
        Object value = input.get(name);
        if (value == null) return fallback;
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(name + " must be an integer", error); }
    }
    /** Converts blank model fields to null for the tool's validated input contract. */
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    /** Serializes event/artifact data and fails closed when a supposedly durable graph state cannot be represented. */
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("cannot serialize graph state", error); }
    }

    /** Adapts graph-tool audit calls to the durable DAG event stream without cross-writing direct-chat run tables. */
    private static final class TaskTraceRepository implements AgentRuntimeRepository {
        private final AgentDagRepository dag;
        private final ObjectMapper mapper;
        /** Stores the outer DAG repository and its canonical JSON serializer. */
        private TaskTraceRepository(AgentDagRepository dag, ObjectMapper mapper) { this.dag = dag; this.mapper = mapper; }
        /** Unsupported because a plan task already owns its conversation lifecycle in AgentDagRepository. */
        @Override 
        /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
        public void createConversation(String conversationId, Instant createdAt) { throw new UnsupportedOperationException("graph task cannot create chat conversation"); }
        /** Unsupported because graph execution is nested in an existing DAG task. */
        @Override 
        /** 执行该 Agent 运行时组件中的 conversationExists 操作。 */
        public boolean conversationExists(String conversationId) { return false; }
        /** Unsupported because graph execution must not create a second agent_run row. */
        @Override 
        /** 创建并初始化当前 Agent 操作所需的 startRun 结果。 */
        public String startRun(String conversationId, String requestId, String promptVersion, String promptHash, String toolSchemaVersion, String modelProvider, String modelName, Instant startedAt) { throw new UnsupportedOperationException("graph task cannot start direct run"); }
        /** No-op because the outer worker owns task completion. */
        @Override 
        /** 通过 completeRun 操作更新持久化或内存中的运行状态。 */
        public void completeRun(String runId, int modelRounds, int toolCalls, com.jijing.fund.agent.api.TokenUsage usage, long durationMs, Instant completedAt) { }
        /** No-op because the outer worker controls retry and failure status. */
        @Override 
        /** 执行该 Agent 运行时组件中的 failRun 操作。 */
        public void failRun(String runId, String status, String errorCode, String safeMessage, int toolCalls, long durationMs, Instant completedAt) { }
        /** Publishes the Spring AI tool audit as a graph-scoped event for the same outer run. */
        @Override 
        /** 通过 recordToolCall 操作更新持久化或内存中的运行状态。 */
        public void recordToolCall(AgentToolCallRecord record) {
            try { dag.appendEvent(record.runId(), "graph.tool." + record.resultStatus().toLowerCase(), mapper.writeValueAsString(Map.of("toolName", record.toolName(), "evidenceIds", record.evidenceIds(), "errorCode", record.errorCode())), Instant.now()); }
            catch (Exception error) { throw new IllegalStateException("cannot publish graph tool event", error); }
        }
    }
}
