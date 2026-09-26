package com.jijing.fund.agent.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.graph.DurableGraphCheckpointSaver;
import com.jijing.fund.agent.graph.GraphCheckpointStore;
import com.jijing.fund.agent.graph.ResumableCatalystResearchGraph;
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
 * 把持久化计划任务接到催化剂研究子图：保存每个节点后的快照，并把工具进度写回既有运行事件。
 * 外层工作者仍负责租约和幂等；用户输入不可纠正或图状态无法序列化时失败。
 */
public final class CatalystResearchCapabilityExecutor implements AgentCapabilityExecutor {
    private final FundCatalystResearchTool tool;
    private final AgentDagRepository dag;
    private final GraphCheckpointStore checkpoints;
    private final ObjectMapper mapper;

    /**
     * 用现有研究工具作为产出证据的角色创建执行器。
     * 依赖为空时构造成功，执行图时才会失败。
     */
    public CatalystResearchCapabilityExecutor(
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
     * 返回工作者注册表允许的催化剂研究能力类型。
     * 不会失败。
     */
    @Override
    public String capabilityType() {
        return "CATALYST_RESEARCH";
    }

    /**
     * 从最近的兼容检查点运行或恢复催化剂图。
     * 工具审计会写成图范围的运行事件，而不是写入不兼容的直接对话运行表。
     * 输入不是合法 JSON、回顾天数不是整数、租约失效或状态无法序列化时失败。
     */
    @Override
    public CapabilityExecutionResult execute(CapabilityExecutionContext context) {
        context.checkActive();
        var task = context.task();
        Map<String, Object> input = CapabilityJson.input(mapper, task.inputJson());
        var request = new ResumableCatalystResearchGraph.Request(
                optional(input, "fundCode"),
                optional(input, "theme"),
                integer(input, "lookbackDays", 45));
        var saver = new DurableGraphCheckpointSaver(
                task.runId(),
                task.taskId(),
                ResumableCatalystResearchGraph.NAME,
                ResumableCatalystResearchGraph.VERSION,
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
                        "graph", ResumableCatalystResearchGraph.NAME,
                        "resumed", resumed)),
                Instant.now()));

        AgentExecutionTrace trace = new AgentExecutionTrace(
                task.runId(),
                new TaskTraceRepository(dag, mapper, context),
                mapper,
                8,
                2,
                Duration.ofSeconds(15));
        var graph = new ResumableCatalystResearchGraph(
                (graphRequest, attempt) -> {
                    context.checkActive();
                    return research(graphRequest, attempt, trace);
                },
                (draft, evidence) -> {
                    context.checkActive();
                    return review(draft, evidence);
                });
        var result = graph.invoke(request, saver, task.taskId());
        Map<String, Object> artifact = Map.of(
                "summary", result.summary(),
                "evidenceIds", result.evidenceIds(),
                "attempts", result.attempts(),
                "graph", ResumableCatalystResearchGraph.NAME,
                "graphVersion", ResumableCatalystResearchGraph.VERSION);
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
     * 用服务端创建的追踪调用研究工具，并把信封转成图状态。
     * 只有上游暂时无数据且是第一次尝试才在图内重试；用户可纠正的输入立即失败。
     */
    private ResumableCatalystResearchGraph.EvidenceDraft research(
            ResumableCatalystResearchGraph.Request request,
            int attempt,
            AgentExecutionTrace trace) {
        var envelope = tool.research(
                new FundCatalystResearchTool.Input(
                        blankToNull(request.theme()),
                        blankToNull(request.fundCode()),
                        10,
                        request.lookbackDays()),
                new ToolContext(Map.of(AgentExecutionTrace.TOOL_CONTEXT_KEY, trace)));
        List<String> evidence = envelope.evidence().stream().map(reference -> reference.evidenceId()).toList();
        String draft = envelope.data() == null ? envelope.safeErrorMessage() : json(envelope.data());
        boolean retryable = envelope.status() == ToolResultStatus.DATA_NOT_READY && attempt == 0;
        if (envelope.status() == ToolResultStatus.USER_CORRECTABLE) {
            throw new IllegalArgumentException(envelope.safeErrorMessage());
        }
        return new ResumableCatalystResearchGraph.EvidenceDraft(draft, evidence, retryable);
    }

    /**
     * 生成带引用边界的审阅文本，不再发起没有依据的第二次工具调用。
     * 证据列表为空时仍返回说明；列表含空元素时会写进文本，不另报错。
     */
    private String review(String draft, List<String> evidenceIds) {
        return "催化剂研究已完成；以下结论仅基于已验证证据 " + String.join(",", evidenceIds) + "。\n" + draft;
    }

    /**
     * 读取可选字符串，并把空白收成空值，避免模型用字面量“null”充数。
     * 不会抛出格式异常。
     */
    private static String optional(Map<String, Object> input, String name) {
        Object value = input.get(name);
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    /**
     * 从任务 JSON 解析整数，缺失时使用服务端默认值。
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
     * 把空白字段收成空值，以符合工具的输入校验。
     * 不会失败。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 序列化事件或产物；无法表示应持久化的图状态时失败关闭。
     * 序列化异常会被包成非法状态。
     */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("cannot serialize graph state", error);
        }
    }

    /**
     * 把图内工具审计改写到计划事件流，避免写入直接对话运行表。
     * 创建会话或启动运行会明确拒绝。
     */
    private static final class TaskTraceRepository implements AgentRuntimeRepository {
        private final AgentDagRepository dag;
        private final ObjectMapper mapper;
        private final CapabilityExecutionContext context;

        /**
         * 保存外层计划存储、映射器和与执行器共用的租约守卫。
         * 不校验依赖是否为空。
         */
        private TaskTraceRepository(AgentDagRepository dag, ObjectMapper mapper, CapabilityExecutionContext context) {
            this.dag = dag;
            this.mapper = mapper;
            this.context = context;
        }

        /**
         * 计划任务的会话生命周期已由计划存储负责，这里不能再创建。
         * 始终抛出不支持操作。
         */
        @Override
        public void createConversation(String conversationId, Instant createdAt) {
            throw new UnsupportedOperationException("graph task cannot create chat conversation");
        }

        /**
         * 图执行嵌在既有计划任务中，不查询对话。
         * 始终返回 false。
         */
        @Override
        public boolean conversationExists(String conversationId) {
            return false;
        }

        /**
         * 图执行不能再插入一行直接运行。
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
         * 外层工作者负责任务完成，这里不结束运行。
         * 调用后没有任何效果。
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
         * 外层工作者控制重试和失败状态，这里不标记运行失败。
         * 调用后没有任何效果。
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
         * 把工具审计发布为同一外层运行上的图事件。
         * 错误码或证据列表为空、载荷无法序列化或租约失效时抛出非法状态。
         */
        @Override
        public void recordToolCall(AgentToolCallRecord record) {
            try {
                String payload = mapper.writeValueAsString(Map.of(
                        "toolName", record.toolName(),
                        "evidenceIds", record.evidenceIds(),
                        "errorCode", record.errorCode()));
                context.persist(() -> dag.appendEvent(
                        record.runId(),
                        "graph.tool." + record.resultStatus().toLowerCase(),
                        payload,
                        Instant.now()));
            } catch (Exception error) {
                throw new IllegalStateException("cannot publish graph tool event", error);
            }
        }
    }
}
