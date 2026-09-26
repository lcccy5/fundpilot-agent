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
 * 带检查点的催化研究流程。恢复由框架保存的节点游标控制。
 * 已完成的图直接返回结果；复核前没有可核验证据时中断。执行失败被包成 IllegalStateException。
 */
public final class ResumableCatalystResearchGraph {
    public static final String NAME = "catalyst-research";
    public static final String VERSION = "v1";
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;

    /**
     * 绑定产出证据和复核证据的角色。任一为 null 时立即抛出 NullPointerException。
     */
    public ResumableCatalystResearchGraph(EvidenceResearcher researcher, ResearchReviewer reviewer) {
        this.researcher = Objects.requireNonNull(researcher, "researcher is required");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer is required");
    }

    /**
     * 启动新图，或让 LangGraph4j 按该任务线程已保存的节点和状态恢复。
     * 状态阶段已是 COMPLETED 时不再执行节点。缺少证据、节点异常或检查点失败时抛出 IllegalStateException。
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
            throw new IllegalStateException("catalyst research graph failed", error);
        }
    }

    /**
     * 编译 route、带一次有界重试的 research，以及 review，并挂上持久化检查点。
     * 不释放线程，因为外层任务可能在图完成后、任务完成提交前崩溃。
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
     * 路由只回写已持久化的阶段，下一个节点仍由 LangGraph4j 的游标决定。阶段缺失时按 NEW 进入研究。
     */
    private Map<String, Object> route(ResearchState state) {
        return Map.of("phase", String.valueOf(state.data().getOrDefault("phase", "NEW")));
    }

    /**
     * 从 JSON 安全状态调用外部研究员，使 JDBC 恢复不依赖 Java 对象序列化。研究员抛出的异常会中断本次恢复。
     */
    private Map<String, Object> research(ResearchState state) {
        Request request = new Request(string(state, "fundCode"), string(state, "theme"), integer(state, "lookbackDays", 45));
        int attempt = integer(state, "attempt", 0);
        EvidenceDraft draft = researcher.research(request, attempt);
        return Map.of("draft", draft.content(), "evidenceIds", draft.evidenceIds(), "retryable", draft.retryable(),
                "attempt", attempt + 1, "phase", "RESEARCH_READY");
    }

    /**
     * 只有至少一条已核验证据编号时才生成最终摘要。证据为空时抛出 IllegalStateException，图不会标成完成。
     */
    private Map<String, Object> review(ResearchState state) {
        List<String> evidence = evidence(state);
        if (evidence.isEmpty()) {
            throw new IllegalStateException("verified evidence is required before review");
        }
        return Map.of("summary", reviewer.review(String.valueOf(state.data().get("draft")), evidence), "phase", "COMPLETED");
    }

    /**
     * 保持原有的单次图内重试：可重试且尝试次数小于 2 才回到研究。恢复后仍使用同一上限。
     */
    private boolean shouldRetry(ResearchState state) {
        return Boolean.TRUE.equals(state.data().get("retryable")) && integer(state, "attempt", 0) < 2;
    }

    /**
     * 从 JSON 安全状态取出不可变证据编号。不是列表时返回空列表，随后复核会因缺少引用失败。
     */
    private static List<String> evidence(ResearchState state) {
        Object value = state.data().get("evidenceIds");
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    /**
     * 从实时状态或已完成的恢复状态组装公开结果。证据缺失时列表为空，不在组装阶段抛错。
     */
    private static Result result(Map<String, Object> state) {
        Object value = state.get("evidenceIds");
        List<String> ids = value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
        return new Result(String.valueOf(state.get("summary")), ids, integer(state, "attempt", 0));
    }

    /**
     * 读取可选请求标量。null 保持为 null，避免把缺失基金代码变成字面量。
     */
    private static String string(ResearchState state, String name) {
        Object value = state.data().get(name);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 解析状态中的数字，缺失时使用服务端默认值。无法解析时抛出 NumberFormatException，并由 invoke 包装。
     */
    private static int integer(ResearchState state, String name, int fallback) {
        return integer(state.data(), name, fallback);
    }

    /**
     * 解析 JSON 反序列化后的数字。null 使用 fallback，其他值按十进制整数解析。
     */
    private static int integer(Map<String, Object> state, String name, int fallback) {
        Object value = state.get(name);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    /**
     * 已校验的图输入。缺少研究对象或回看天数越界时不能构造，图不会启动。
     */
    public record Request(String fundCode, String theme, int lookbackDays) implements java.io.Serializable {
        /**
         * 拒绝空白研究对象和越界回看天数。失败发生在图和检查点之外。
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
     * 证据角色的产出。证据列表为 null 时变成空列表，复核会因此拒绝生成无引用摘要。
     */
    public record EvidenceDraft(String content, List<String> evidenceIds, boolean retryable) implements java.io.Serializable {
        /**
         * 冻结证据编号。null 收成空列表，不在此抛出缺少引用。
         */
        public EvidenceDraft {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * 交给外层能力执行器的最终图结果。摘要文本本身不强制包含引用标记。
     */
    public record Result(String summary, List<String> evidenceIds, int attempts) implements java.io.Serializable {}

    /**
     * 产出证据的角色，可以调用 Spring AI 工具。工具失败应抛出，而不是返回无法追溯的结论。
     */
    @FunctionalInterface
    public interface EvidenceResearcher {
        /**
         * 按尝试次数产出草稿。空证据且不再重试会使复核因缺少引用中断。
         */
        EvidenceDraft research(Request request, int attempt);
    }

    /**
     * 保留引用的复核角色。只有证据列表非空时才会被调用。
     */
    @FunctionalInterface
    public interface ResearchReviewer {
        /**
         * 根据草稿和证据编号生成摘要。抛出异常时图失败，完成阶段不会写入。
         */
        String review(String draft, List<String> evidenceIds);
    }

    /**
     * JSON 安全的 LangGraph4j 状态包装。拷贝映射，避免节点修改外部持有的状态。
     */
    static final class ResearchState extends AgentState {
        /**
         * 用输入副本初始化状态。data 为 null 时由父类失败。
         */
        ResearchState(Map<String, Object> data) {
            super(new LinkedHashMap<>(data));
        }
    }
}
