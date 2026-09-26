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
 * 单次研究能力内部的进程内图。它不拥有运行、任务或租约，那些持久化问题留在外层 AgentDagRepository。
 * 研究或复核抛出的异常会被包成 IllegalStateException；本图不使用框架中断，失败即停止。
 */
public final class CatalystResearchGraph {
    private final EvidenceResearcher researcher;
    private final ResearchReviewer reviewer;

    /**
     * 绑定产出证据和复核证据的两个角色。任一角色为 null 时立即抛出 NullPointerException。
     */
    public CatalystResearchGraph(EvidenceResearcher researcher, ResearchReviewer reviewer) {
        this.researcher = Objects.requireNonNull(researcher, "researcher is required");
        this.reviewer = Objects.requireNonNull(reviewer, "reviewer is required");
    }

    /**
     * 执行研究节点，最多在图内重试一次，再交给复核节点生成摘要。
     * 编译、节点或复核失败时抛出 IllegalStateException，原因保留在 cause 中。没有检查点，失败后不能从中断处恢复。
     */
    public Result invoke(Request request) {
        try {
            CompiledGraph<ResearchState> graph = graph();
            ResearchState state = graph.invoke(Map.of("request", request, "attempt", 0)).orElseThrow();
            return new Result(String.valueOf(state.data().get("summary")), evidence(state),
                    Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))));
        } catch (Exception error) {
            throw new IllegalStateException("catalyst research graph failed", error);
        }
    }

    /**
     * 编译 research 与 synthesize 两节点。条件边在可重试且尝试次数小于 2 时回到 research。
     * 图定义非法时抛出 Exception，由 invoke 统一包装。
     */
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

    /**
     * 调用外部研究员并写回草稿、证据编号和是否可重试。研究员抛出的异常会中断图，不在节点内吞掉。
     */
    private Map<String, Object> research(ResearchState state) {
        Request request = (Request) state.data().get("request");
        int attempt = Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0)));
        EvidenceDraft draft = researcher.research(request, attempt);
        return Map.of("draft", draft.content(), "evidenceIds", draft.evidenceIds(), "retryable", draft.retryable(), "attempt", attempt + 1);
    }

    /**
     * 把草稿和已有证据交给复核角色。证据列表为空时仍调用复核，由复核角色决定是否因缺少引用失败。
     */
    private Map<String, Object> synthesize(ResearchState state) {
        String summary = reviewer.review(String.valueOf(state.data().get("draft")), evidence(state));
        return Map.of("summary", summary);
    }

    /**
     * 仅当草稿标记可重试且已完成的尝试次数小于 2 时回到研究节点。达到上限后进入复核，即使仍标记可重试。
     */
    private boolean shouldRetry(ResearchState state) {
        return Boolean.TRUE.equals(state.data().get("retryable"))
                && Integer.parseInt(String.valueOf(state.data().getOrDefault("attempt", 0))) < 2;
    }

    /**
     * 从状态里取出证据编号。值不是列表时返回空列表，表示当前没有可引用证据，不抛出异常。
     */
    @SuppressWarnings("unchecked")
    private static List<String> evidence(ResearchState state) {
        Object value = state.data().get("evidenceIds");
        return value instanceof List<?> values ? values.stream().map(String::valueOf).toList() : List.of();
    }

    /**
     * 图的输入。基金代码和主题不能同时为空白，回看天数必须在 7 到 180。
     * 违反时构造即抛出 IllegalArgumentException，不会进入图。
     */
    public record Request(String fundCode, String theme, int lookbackDays) implements java.io.Serializable {
        /**
         * 拒绝缺少研究对象或越界回看天数的输入。两个条件都失败时只报告研究对象缺失。
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
     * 研究节点的草稿。evidenceIds 为 null 时收成空列表，表示这次没有可引用证据，retryable 仍由调用方决定。
     */
    public record EvidenceDraft(String content, List<String> evidenceIds, boolean retryable) implements java.io.Serializable {
        /**
         * 冻结证据编号列表。传入 null 时改为空列表，不把缺少引用当成构造失败。
         */
        public EvidenceDraft {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    /**
     * 图的最终摘要、证据编号和实际研究次数。摘要缺少引用时不会在这个类型里被拒绝。
     */
    public record Result(String summary, List<String> evidenceIds, int attempts) implements java.io.Serializable {}

    /**
     * 产出研究草稿的角色，可以调用 Spring AI 工具。失败时应抛出异常，让图停止而不是返回无证据结论。
     */
    @FunctionalInterface
    public interface EvidenceResearcher {
        /**
         * 按尝试次数产出草稿。attempt 从 0 开始；无法取得证据时可以返回空列表并标记可重试，或直接抛出异常中断图。
         */
        EvidenceDraft research(Request request, int attempt);
    }

    /**
     * 保留引用的复核角色。草稿没有证据时由实现决定是拒绝还是写明缺少引用。
     */
    @FunctionalInterface
    public interface ResearchReviewer {
        /**
         * 根据草稿和证据编号生成摘要。实现可以因 evidenceIds 为空抛出异常，图会把该失败包装后抛出。
         */
        String review(String draft, List<String> evidenceIds);
    }

    /**
     * LangGraph4j 状态包装。拷贝输入映射，避免节点修改调用方持有的原映射。
     */
    static final class ResearchState extends AgentState {
        /**
         * 用调用方数据的副本初始化状态。data 为 null 时由父类失败，本构造器不另做校验。
         */
        ResearchState(Map<String, Object> data) {
            super(new LinkedHashMap<>(data));
        }
    }
}
