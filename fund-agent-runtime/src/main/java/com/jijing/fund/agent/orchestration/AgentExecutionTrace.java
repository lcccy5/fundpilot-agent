package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.FundAgentEvent;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.exception.AgentModeEscalationException;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 记录一次有界运行中的工具调用、证据和事实卡。
 * 重复调用或工具预算耗尽时分别抛出执行限制或模式升级异常，调用方必须停止本轮工具循环。
 * 单次工具失败只记失败审计，不取消已经成功的证据。事实卡写入失败被吞掉，不能反过来让工具调用失败。
 * 计划校验、路由权限和审批拒绝发生在本追踪之外。
 */
public final class AgentExecutionTrace {
    public static final String TOOL_CONTEXT_KEY = "fundAgentTrace";
    public static final String USER_CONTEXT_KEY = "fundAgentUser";
    private static final ExecutorService TOOL_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("fund-agent-tool-", 0).factory());
    private final String runId;
    private final String conversationId;
    private final AgentRuntimeRepository repository;
    private final ObjectMapper mapper;
    private final int maxToolCalls;
    private final int maxRepeatedCalls;
    private final Duration toolTimeout;
    private final List<EvidenceReference> evidence = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Integer> callSignatures = new ConcurrentHashMap<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final Consumer<FundAgentEvent> events;
    private final Duration defaultFactTtl;

    /**
     * 创建不绑定会话、默认事实卡有效一天、且不向外发送事件的追踪。
     * 预算在 {@link #begin} 时生效；构造本身不触碰计划或审批。
     */
    public AgentExecutionTrace(String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout) {
        this(null, runId, repository, mapper, maxToolCalls, maxRepeatedCalls, toolTimeout, Duration.ofHours(24),
                event -> {
                });
    }

    /**
     * 创建不绑定会话、默认事实卡有效一天的追踪，并把工具事件交给调用方。
     * 事件消费者为 null 时由下一构造器拒绝，避免工具失败被静默丢掉。
     */
    public AgentExecutionTrace(String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout, Consumer<FundAgentEvent> events) {
        this(null, runId, repository, mapper, maxToolCalls, maxRepeatedCalls, toolTimeout, Duration.ofHours(24), events);
    }

    /**
     * 创建绑定会话的追踪。工具超时为 null 时立即失败。
     * 默认事实卡有效期为 null 时按一天处理。没有会话标识时成功调用不会写事实卡。
     */
    public AgentExecutionTrace(String conversationId, String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout, Duration defaultFactTtl,
            Consumer<FundAgentEvent> events) {
        this.conversationId = conversationId;
        this.runId = runId;
        this.repository = repository;
        this.mapper = mapper;
        this.maxToolCalls = maxToolCalls;
        this.maxRepeatedCalls = maxRepeatedCalls;
        this.toolTimeout = Objects.requireNonNull(toolTimeout);
        this.defaultFactTtl = defaultFactTtl == null ? Duration.ofHours(24) : defaultFactTtl;
        this.events = Objects.requireNonNull(events);
    }

    /**
     * 登记一次即将执行的工具调用。
     * 相同参数的重复次数超过上限时抛出 {@link AgentExecutionLimitException}，本次不计入可升级的预算耗尽。
     * 工具次数超过上限时抛出 {@link AgentModeEscalationException}，调用方可以把有界运行升级为持久化计划。
     */
    public ToolInvocation begin(String toolName, Object arguments) {
        String redacted = safeJson(arguments);
        String signature = toolName + ":" + sha256(redacted);
        int repeats = callSignatures.merge(signature, 1, Integer::sum);
        if (repeats > maxRepeatedCalls) {
            throw new AgentExecutionLimitException("Repeated identical tool call blocked: " + toolName);
        }
        if (toolCalls.incrementAndGet() > maxToolCalls) {
            throw new AgentModeEscalationException("Bounded ReAct tool budget exhausted");
        }
        var invocation = new ToolInvocation(toolName, redacted, signature.substring(signature.indexOf(':') + 1),
                Instant.now());
        events.accept(FundAgentEvent.of("tool.started", runId, Map.of("toolName", toolName, "arguments", redacted)));
        return invocation;
    }

    /**
     * 在工具超时内执行操作。
     * 超时或中断时取消任务并抛出 {@link AgentExecutionLimitException}，中断标志会保留。
     * 操作抛出的运行时异常原样冒出；受检异常包成 {@link IllegalStateException}。这些失败不自动升级路由。
     */
    public <T> T call(ToolInvocation invocation, Callable<T> operation) {
        Future<T> future = TOOL_EXECUTOR.submit(operation);
        try {
            return future.get(toolTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new AgentExecutionLimitException("Tool execution timed out: " + invocation.toolName());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentExecutionLimitException("Tool execution interrupted: " + invocation.toolName());
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Tool execution failed: " + invocation.toolName(), cause);
        }
    }

    /**
     * 记录一次成功调用及其单条证据。
     * 证据为 null 时只记成功、不增加证据列表。审计写入失败会向外抛出。
     */
    public void success(ToolInvocation invocation, EvidenceReference reference) {
        if (reference != null) {
            evidence.add(reference);
        }
        finish(invocation, "SUCCESS", reference == null ? List.of() : List.of(reference.evidenceId()), null);
    }

    /**
     * 记录成功调用，并在有证据和观察数据时尝试保存事实卡。
     * 事实卡失败被忽略，成功审计仍然保留。
     */
    public void success(ToolInvocation invocation, EvidenceReference reference, Object observedData) {
        success(invocation, reference);
        saveFactCard(invocation, reference == null ? List.of() : List.of(reference), observedData);
    }

    /**
     * 记录一次带多条证据的成功调用。
     * 证据会全部加入本轮列表。随后的引用校验只承认这些标识。
     */
    public void success(ToolInvocation invocation, List<EvidenceReference> references) {
        evidence.addAll(references);
        finish(invocation, "SUCCESS", references.stream().map(EvidenceReference::evidenceId).toList(), null);
    }

    /**
     * 记录多证据成功调用，并尝试把观察数据写成事实卡。
     * 事实卡写失败不改变已经记录的成功状态。
     */
    public void success(ToolInvocation invocation, List<EvidenceReference> references, Object observedData) {
        success(invocation, references);
        saveFactCard(invocation, references, observedData);
    }

    /**
     * 把本次工具调用记为失败，不增加证据。
     * 错误码写入审计和 tool.failed 事件。失败不会自动触发重规划或审批。
     */
    public void failure(ToolInvocation invocation, String errorCode) {
        finish(invocation, "FAILED", List.of(), errorCode);
    }

    /**
     * 持久化工具调用结果并发出完成或失败事件。
     * 结果不是 SUCCESS 时事件类型为 tool.failed。错误码为 null 时不写入事件载荷。
     */
    private void finish(ToolInvocation invocation, String result, List<String> evidenceIds, String errorCode) {
        Instant completed = Instant.now();
        repository.recordToolCall(new AgentToolCallRecord(runId, invocation.toolName(), "fund-tools-v1",
                invocation.argumentHash(), invocation.redactedArguments(), result, evidenceIds, errorCode,
                Duration.between(invocation.startedAt(), completed).toMillis(), invocation.startedAt(), completed));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("toolName", invocation.toolName());
        event.put("result", result);
        event.put("evidenceIds", evidenceIds);
        event.put("durationMs", Duration.between(invocation.startedAt(), completed).toMillis());
        if (errorCode != null) {
            event.put("errorCode", errorCode);
        }
        events.accept(FundAgentEvent.of("SUCCESS".equals(result) ? "tool.completed" : "tool.failed", runId, event));
    }

    /**
     * 返回本轮已经收集的证据快照。
     * 工具失败不会移除此前的成功证据。
     */
    public List<EvidenceReference> evidence() {
        synchronized (evidence) {
            return List.copyOf(evidence);
        }
    }

    /**
     * 把已信任的证据放入本轮列表，供引用校验使用。
     * 传入 null 时不做任何事。这些证据不代表新的工具调用成功。
     */
    public void seedEvidence(Collection<EvidenceReference> trustedEvidence) {
        if (trustedEvidence != null) {
            evidence.addAll(trustedEvidence);
        }
    }

    /**
     * 返回已经开始的工具次数，含触发预算异常的那一次递增。
     * 被重复签名提前拒绝的调用不增加该计数。
     */
    public int toolCalls() {
        return toolCalls.get();
    }

    /**
     * 把成功观察写成会话事实卡。
     * 没有会话、没有观察数据或没有证据时直接返回。序列化或存储抛出的异常被忽略，
     * 以免事实记忆失败推翻已经成功的工具调用。超长 JSON 会先截成预览再保存。
     */
    private void saveFactCard(ToolInvocation invocation, List<EvidenceReference> references, Object observedData) {
        if (conversationId == null || conversationId.isBlank() || observedData == null || references.isEmpty()) {
            return;
        }
        try {
            String data = mapper.writeValueAsString(observedData);
            if (data.length() > 16000) {
                data = mapper.writeValueAsString(Map.of("truncated", true, "preview", data.substring(0, 12000)));
            }
            String subject = references.stream()
                    .map(EvidenceReference::fundCode)
                    .filter(Objects::nonNull)
                    .distinct()
                    .reduce((left, right) -> left + "," + right)
                    .orElse(null);
            Instant created = Instant.now();
            Duration ttl = ttl(invocation.toolName());
            repository.saveFactCard(new AgentFactCard(UUID.randomUUID().toString(), conversationId, runId,
                    invocation.toolName(), subject, references, data, created, created.plus(ttl)));
        } catch (RuntimeException ignored) {
            // 事实记忆失败不得让本次工具调用失败。
        } catch (Exception ignored) {
            // 序列化失败时降级为不写事实卡。
        }
    }

    /**
     * 按工具名选择事实卡有效期。
     * 实时数据五分钟，板块和持仓类一小时，文档三十天，概况七天，其余使用默认有效期。
     */
    private Duration ttl(String toolName) {
        String name = toolName.toLowerCase(Locale.ROOT);
        if (name.contains("realtime")) {
            return Duration.ofMinutes(5);
        }
        if (name.contains("sector") || name.contains("event") || name.contains("holding")
                || name.contains("impact") || name.contains("industry")) {
            return Duration.ofHours(1);
        }
        if (name.contains("document")) {
            return Duration.ofDays(30);
        }
        if (name.contains("profile")) {
            return Duration.ofDays(7);
        }
        return defaultFactTtl;
    }

    /**
     * 把工具参数序列化成可审计文本。
     * 序列化失败时返回空对象文本，不因此拒绝本次调用。
     */
    private String safeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     * 算法不可用时抛出 {@link IllegalStateException}，调用方应中止依赖该摘要的工具签名。
     */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 一次已登记、尚未完成的工具调用。
     * 参数摘要用于重复检测；调用失败时仍用它写审计，不会重新生成签名。
     */
    public record ToolInvocation(String toolName, String redactedArguments, String argumentHash, Instant startedAt) {
    }
}
