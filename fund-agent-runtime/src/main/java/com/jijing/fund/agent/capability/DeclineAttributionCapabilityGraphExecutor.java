package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.DurableGraphCheckpointSaver;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import com.jijing.fund.agent.graph.ResumableDeclineAttributionGraph;
import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import com.jijing.fund.agent.tool.FundCatalystResearchTool;
import com.jijing.fund.agent.tool.ToolResultStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;

/**
 * 在持久化计划中把下跌归因作为可恢复的图任务执行。
 * 用户输入不可纠正时立即失败；租约失效时由上下文中断，不把半成品标成成功。
 */
public final class DeclineAttributionCapabilityGraphExecutor implements AgentCapabilityExecutor {
    private final FundCatalystResearchTool tool;
    private final AgentDagRepository dag;
    private final GraphCheckpointStore checkpoints;
    private final ObjectMapper mapper;

    /**
     * 注入研究工具、计划存储、检查点存储和 JSON 映射器。
     * 依赖为空时构造成功，执行图时才会失败。
     */
    public DeclineAttributionCapabilityGraphExecutor(
            FundCatalystResearchTool tool,
            AgentDagRepository dag,
            GraphCheckpointStore checkpoints,
            ObjectMapper mapper) {
        this.tool = tool;
        this.dag = dag;
        this.checkpoints = checkpoints;
        this.mapper = mapper;
    }

    /**
     * 返回下跌归因能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "DECLINE_ATTRIBUTION";
    }

    /**
     * 从最近的兼容检查点运行或恢复归因图，并把进度写回同一运行的事件流。
     * 输入不是合法 JSON、整数无法解析、图状态无法序列化或租约失效时失败。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        context.checkActive();
        var task = context.task();
        Map<String, Object> input = CapabilityJson.input(mapper, task.inputJson());
        String fundCode = optional(input, "fundCode");
        String theme = optional(input, "theme");
        int lookbackDays = integer(input, "lookbackDays", 45);
        var request = new ResumableDeclineAttributionGraph.Request(fundCode, theme, lookbackDays);
        var saver = new DurableGraphCheckpointSaver(
                task.runId(),
                task.taskId(),
                ResumableDeclineAttributionGraph.NAME,
                ResumableDeclineAttributionGraph.VERSION,
                checkpoints,
                context,
                dag,
                mapper);
        boolean resumed = saver.get(org.bsc.langgraph4j.RunnableConfig.builder().threadId(task.taskId()).build())
                .isPresent();
        context.persist(() -> dag.appendEvent(
                task.runId(),
                "graph.started",
                json(Map.of(
                        "taskKey", task.taskKey(),
                        "graph", ResumableDeclineAttributionGraph.NAME,
                        "resumed", resumed)),
                Instant.now()));
        AgentExecutionTrace trace = new AgentExecutionTrace(
                task.runId(),
                new TaskTraceRepository(dag, mapper, context),
                mapper,
                8,
                1,
                Duration.ofSeconds(15));
        var graph = new ResumableDeclineAttributionGraph(
                (graphRequest, attempt) -> {
                    context.checkActive();
                    return research(graphRequest, attempt, trace);
                },
                (draft, evidence) -> "下跌原因归因已完成；以下结论仅基于已验证证据 "
                        + String.join(",", evidence) + "。\n" + draft);
        var result = graph.invoke(request, saver, task.taskId());
        Map<String, Object> artifact = Map.of(
                "summary", result.summary(),
                "evidenceIds", result.evidenceIds(),
                "attempts", result.attempts(),
                "graph", ResumableDeclineAttributionGraph.NAME,
                "graphVersion", ResumableDeclineAttributionGraph.VERSION);
        context.persist(() -> dag.appendEvent(
                task.runId(),
                "graph.completed",
                json(Map.of(
                        "taskKey", task.taskKey(),
                        "evidenceCount", result.evidenceIds().size(),
                        "attempts", result.attempts())),
                Instant.now()));
        return new CapabilityExecutionResult(
                CapabilityJson.artifactUri(mapper, context, artifact),
                result.evidenceIds());
    }

    /**
     * 调用研究工具并把结果信封转成图状态。
     * 用户可纠正的输入立即抛出非法参数；仅第一次数据未就绪才允许图内重试。
     */
    private ResumableDeclineAttributionGraph.EvidenceDraft research(
            ResumableDeclineAttributionGraph.Request request,
            int attempt,
            AgentExecutionTrace trace) {
        String theme = request.theme() == null ? "分析基金近期下跌原因及相关风险事件" : request.theme();
        var envelope = tool.research(
                new FundCatalystResearchTool.Input(theme, request.fundCode(), 10, request.lookbackDays()),
                new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace)));
        List<String> evidence = envelope.evidence().stream().map(value -> value.evidenceId()).toList();
        String draft = envelope.data() == null ? envelope.safeErrorMessage() : json(envelope.data());
        boolean retryable = envelope.status() == ToolResultStatus.DATA_NOT_READY && attempt == 0;
        if (envelope.status() == ToolResultStatus.USER_CORRECTABLE) {
            throw new IllegalArgumentException(envelope.safeErrorMessage());
        }
        return new ResumableDeclineAttributionGraph.EvidenceDraft(draft, evidence, retryable);
    }

    /**
     * 读取可选文本；空白与空值都视为没有提供。
     * 不会抛出格式异常。
     */
    private static String optional(Map<String, Object> input, String name) {
        Object value = input.get(name);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /**
     * 从任务 JSON 读取整数，缺失时使用服务端默认值。
     * 值存在但不是整数时抛出非法参数。
     */
    private static int integer(Map<String, Object> input, String name, int fallback) {
        Object value = input.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
    }

    /**
     * 序列化图状态或事件载荷。
     * 无法序列化时抛出非法状态，避免写下无法恢复的检查点。
     */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize graph state", error);
        }
    }

    /**
     * 把工具审计改写到所属计划任务的事件流，不另开一次直接对话运行。
     * 创建会话或启动运行会明确拒绝；完成和失败由外层工作者负责，这里不做。
     */
    private static final class TaskTraceRepository implements AgentRuntimeRepository {
        private final AgentDagRepository dag;
        private final ObjectMapper mapper;
        private final CapabilityExecutionContext context;

        /**
         * 保存外层计划存储、映射器和租约上下文。
         * 不校验依赖是否为空。
         */
        private TaskTraceRepository(AgentDagRepository dag, ObjectMapper mapper, CapabilityExecutionContext context) {
            this.dag = dag;
            this.mapper = mapper;
            this.context = context;
        }

        /**
         * 拒绝在图任务里创建对话。
         * 始终抛出不支持操作。
         */
        @Override
        public void createConversation(String conversationId, Instant createdAt) {
            throw new UnsupportedOperationException("graph task cannot create chat conversation");
        }

        /**
         * 图任务不查询对话是否存在。
         * 始终返回 false，不访问存储。
         */
        @Override
        public boolean conversationExists(String conversationId) {
            return false;
        }

        /**
         * 拒绝在图任务里再启动一次直接运行。
         * 始终抛出不支持操作。
         */
        @Override
        public String startRun(
                String conversationId,
                String requestId,
                String promptVersion,
                String promptHash,
                String toolSchemaVersion,
                String modelProvider,
                String modelName,
                Instant startedAt) {
            throw new UnsupportedOperationException("graph task cannot start direct run");
        }

        /**
         * 不在图内结束外层运行，完成状态由工作者写入。
         * 调用后没有任何效果，也不失败。
         */
        @Override
        public void completeRun(
                String runId,
                int modelRounds,
                int toolCalls,
                com.jijing.fund.agent.api.TokenUsage usage,
                long durationMs,
                Instant completedAt) {
        }

        /**
         * 不在图内把外层运行标失败，重试和失败由工作者控制。
         * 调用后没有任何效果，也不失败。
         */
        @Override
        public void failRun(
                String runId,
                String status,
                String errorCode,
                String safeMessage,
                int toolCalls,
                long durationMs,
                Instant completedAt) {
        }

        /**
         * 把工具审计写成同一外层运行上的图事件。
         * 载荷无法序列化或租约已失效时抛出非法状态。
         */
        @Override
        public void recordToolCall(AgentToolCallRecord record) {
            try {
                String payload = mapper.writeValueAsString(Map.of(
                        "toolName", record.toolName(),
                        "evidenceIds", record.evidenceIds(),
                        "errorCode", recordDual(record.errorCode())));
                context.persist(() -> dag.appendEvent(
                        record.runId(),
                        "graph.tool." + record.resultStatus().toLowerCase(),
                        payload,
                        Instant.now()));
            } catch (Exception error) {
                throw new IllegalStateException("cannot publish graph tool event", error);
            }
        }

        /**
         * 把空错误码收成空字符串，避免事件载荷拒绝空值。
         * 不会失败。
         */
        private static String recordDual(String value) {
            return value == null ? "" : value;
        }
    }
}
