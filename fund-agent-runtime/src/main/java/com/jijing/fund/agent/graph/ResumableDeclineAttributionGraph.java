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

/**
 * 带检查点的下跌归因证据循环。恢复位置由已保存的图游标决定，而不是由应用层重选节点。
 * 复核前没有证据编号时会中断；图执行失败被包成 IllegalStateException，本类不调用框架的 interrupt。
 */
public final class ResumableDeclineAttributionGraph {
    public static final String NAME = "decline-attribution";
    public static final String VERSION = "v1";
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;

    /**
     * 绑定产出证据和复核证据的角色。任一为 null 时立即抛出 NullPointerException。
     */
    public ResumableDeclineAttributionGraph(EvidenceResearcher researcher, ResearchReviewer reviewer) {
        this.researcher = Objects.requireNonNull(researcher, "researcher is required");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer is required");
    }

    /**
     * 启动新的归因图，或从该任务线程已保存的节点和状态恢复。已完成阶段直接返回保存的结果，不再调用研究员。
     * 复核缺少证据、节点失败或检查点读写失败时抛出 IllegalStateException。
     */
    public Result invoke(Request request, DurableGraphCheckpointSaver saver, String taskId) {
        try {
            RunnableConfig config = RunnableConfig.builder().threadId(taskId).graphId(NAME + ":" + VERSION).build();
            var saved = saver.get(config);
            if (saved.filter(value -> "COMPLETED".equals(value.getState().get("phase"))).isPresent()) {
                return result(saved.orElseThrow().getState());
            }
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("fundCode", request.fundCode());
            input.put("theme", request.theme());
            input.put("lookbackDays", request.lookbackDays());
            input.put("attempt", 0);
            input.put("phase", "NEW");
            ResearchState state = graph(saver).invoke(saved.isPresent() ? GraphInput.resume() : GraphInput.args(input), config).orElseThrow();
            return result(state.data());
        } catch (Exception error) {
            throw new IllegalStateException("decline attribution graph failed", error);
        }
    }

    /**
     * 编译 route、research、review。研究节点最多再试一次；复核前必须已有证据。
     * 不释放线程，以便图完成后、外层任务提交前仍能从检查点读回结果。
     */
    private CompiledGraph<ResearchState> graph(DurableGraphCheckpointSaver saver) throws Exception {
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
        return graph.compile(CompileConfig.builder().checkpointSaver(saver).releaseThread(false).graphId(NAME + ":" + VERSION).build());
    }

    /**
     * 把已持久化的阶段原样写回，供条件边选择研究或复核。阶段缺失时记为 NEW，从而进入研究而不是复核。
     */
    private Map<String, Object> route(ResearchState state) {
        return Map.of("phase", String.valueOf(state.data().getOrDefault("phase", "NEW")));
    }

    /**
     * 用 JSON 安全的标量重建请求并调用研究员。研究员失败时异常穿出，检查点停在进入本节点之前的游标。
     */
    private Map<String, Object> research(ResearchState state) {
        Request request = new Request(string(state, "fundCode"), string(state, "theme"), integer(state, "lookbackDays", 45));
        int attempt = integer(state, "attempt", 0);
        EvidenceDraft draft = researcher.research(request, attempt);
        return Map.of("draft", draft.content(), "evidenceIds", draft.evidenceIds(), "retryable", draft.retryable(),
                "attempt", attempt + 1, "phase", "RESEARCH_READY");
    }

    /**
     * 仅在至少有一条证据编号时生成最终摘要。证据为空时抛出 IllegalStateException，避免无引用结论被标成完成。
     */
    private Map<String, Object> review(ResearchState state) {
        List<String> evidence = evidence(state);
        if (evidence.isEmpty()) {
            throw new IllegalStateException("verified evidence is required before review");
        }
        return Map.of("summary", reviewer.review(String.valueOf(state.data().get("draft")), evidence), "phase", "COMPLETED");
    }

    /**
     * 草稿可重试且尝试次数小于 2 时回到研究节点。达到上限后进入复核；若仍没有证据，复核会因缺少引用失败。
     */
    private boolean shouldRetry(ResearchState state) {
        return Boolean.TRUE.equals(state.data().get("retryable")) && integer(state, "attempt", 0) < 2;
    }

    /**
     * 从状态取出证据编号。值不是列表时返回空列表，复核会据此拒绝继续。
     */
    private static List<String> evidence(ResearchState state) {
        Object value = state.data().get("evidenceIds");
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    /**
     * 从实时状态或已完成检查点组装公开结果。证据不是列表时得到空列表，不在这里抛出缺少引用的异常。
     */
    private static Result result(Map<String, Object> state) {
        Object value = state.get("evidenceIds");
        List<String> ids = value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        return new Result(String.valueOf(state.get("summary")), ids, integer(state, "attempt", 0));
    }

    /**
     * 读取可选请求标量。值为 null 时返回 null，而不是字符串 "null"。
     */
    private static String string(ResearchState state, String name) {
        Object value = state.data().get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 从状态读取整数，缺失时使用图的默认值。值无法解析时抛出 NumberFormatException，并由 invoke 包装。
     */
    private static int integer(ResearchState state, String name, int fallback) {
        return integer(state.data(), name, fallback);
    }

    /**
     * 从映射读取整数。JSON 反序列化后的数字会先变成字符串再解析；null 使用 fallback。
     */
    private static int integer(Map<String, Object> state, String name, int fallback) {
        Object value = state.get(name);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 归因图输入。基金代码和主题不能同时为空白，回看天数必须在 7 到 180，否则构造失败。
     */
    public record Request(String fundCode, String theme, int lookbackDays) {
        /**
         * 拒绝缺少研究对象或越界回看天数。不会进入图，也不会写检查点。
         */
        public Request {
            if ((fundCode == null || fundCode.isBlank()) && (theme == null || theme.isBlank())) {
                throw new IllegalArgumentException("fundCode or theme is required");
            }
            if (lookbackDays < 7 || lookbackDays > 180) {
                throw new IllegalArgumentException("lookbackDays must be 7..180");
            }
        }
    }

    /**
     * 研究角色产出的草稿。证据列表为 null 时收成空列表，复核阶段会因缺少引用失败。
     */
    public record EvidenceDraft(String content, List<String> evidenceIds, boolean retryable) {
        /**
         * 冻结证据编号。null 改为空列表，不在草稿构造时抛出缺少引用。
         */
        public EvidenceDraft {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * 交给外层能力执行器的最终结果。attempts 来自状态中的尝试计数，缺失时按 0。
     */
    public record Result(String summary, List<String> evidenceIds, int attempts) {}

    /**
     * 产出证据的角色，可以调用 Spring AI 工具。失败应抛出异常，让检查点留在研究节点之前。
     */
    @FunctionalInterface
    public interface EvidenceResearcher {
        /**
         * 按尝试次数产出草稿。返回空证据且不再重试时，后续复核会因缺少引用中断。
         */
        EvidenceDraft research(Request request, int attempt);
    }

    /**
     * 保留引用的复核角色。调用发生在证据列表非空之后，实现仍可因草稿不可复核而抛出异常。
     */
    @FunctionalInterface
    public interface ResearchReviewer {
        /**
         * 根据草稿和证据编号生成摘要。抛出的异常会使图失败，已完成阶段不会被写入。
         */
        String review(String draft, List<String> evidenceIds);
    }

    /**
     * JSON 安全的 LangGraph4j 状态包装，避免 JDBC 恢复依赖 Java 对象序列化。
     */
    static final class ResearchState extends AgentState {
        /**
         * 用输入映射的副本初始化状态。data 为 null 时由父类失败。
         */
        ResearchState(Map<String, Object> data) {
            super(new LinkedHashMap<>(data));
        }
    }
}
