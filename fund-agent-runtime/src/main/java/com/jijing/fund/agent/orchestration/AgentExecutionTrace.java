package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.FundAgentEvent;
import com.jijing.fund.agent.exception.AgentExecutionLimitException;
import com.jijing.fund.agent.port.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 实现 AgentExecutionTrace 所代表的 Agent 运行时职责。 */
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
    private final Map<String,Integer> callSignatures = new ConcurrentHashMap<>();
    private final AtomicInteger toolCalls = new AtomicInteger();
    private final Consumer<FundAgentEvent> events;
    private final Duration defaultFactTtl;

    
    /** 执行该 Agent 运行时组件中的 AgentExecutionTrace 操作。 */
    public AgentExecutionTrace(String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout) {
        this(null,runId,repository,mapper,maxToolCalls,maxRepeatedCalls,toolTimeout,Duration.ofHours(24),event->{});
    }
    
    /** 执行该 Agent 运行时组件中的 AgentExecutionTrace 操作。 */
    public AgentExecutionTrace(String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout,Consumer<FundAgentEvent> events) {
        this(null,runId,repository,mapper,maxToolCalls,maxRepeatedCalls,toolTimeout,Duration.ofHours(24),events);
    }
    
    /** 执行该 Agent 运行时组件中的 AgentExecutionTrace 操作。 */
    public AgentExecutionTrace(String conversationId,String runId, AgentRuntimeRepository repository, ObjectMapper mapper,
            int maxToolCalls, int maxRepeatedCalls, Duration toolTimeout,Duration defaultFactTtl,Consumer<FundAgentEvent> events) {
        this.conversationId=conversationId;this.runId=runId; this.repository=repository; this.mapper=mapper;
        this.maxToolCalls=maxToolCalls; this.maxRepeatedCalls=maxRepeatedCalls;
        this.toolTimeout=Objects.requireNonNull(toolTimeout);this.defaultFactTtl=defaultFactTtl==null?Duration.ofHours(24):defaultFactTtl;this.events=Objects.requireNonNull(events);
    }

    
    /** 执行该 Agent 运行时组件中的 begin 操作。 */
    public ToolInvocation begin(String toolName, Object arguments) {
        String redacted = safeJson(arguments);
        String signature = toolName + ":" + sha256(redacted);
        int repeats = callSignatures.merge(signature, 1, Integer::sum);
        if (repeats > maxRepeatedCalls) throw new AgentExecutionLimitException("Repeated identical tool call blocked: " + toolName);
        if (toolCalls.incrementAndGet() > maxToolCalls) throw new AgentExecutionLimitException("Maximum tool calls exceeded");
        var invocation=new ToolInvocation(toolName, redacted, signature.substring(signature.indexOf(':')+1), Instant.now());
        events.accept(FundAgentEvent.of("tool.started",runId,Map.of("toolName",toolName,"arguments",redacted)));
        return invocation;
    }

    
    /** 执行该 Agent 运行时组件中的 call 操作。 */
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
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Tool execution failed: " + invocation.toolName(), cause);
        }
    }

    
    /** 执行该 Agent 运行时组件中的 success 操作。 */
    public void success(ToolInvocation invocation, EvidenceReference reference) {
        if (reference != null) evidence.add(reference);
        finish(invocation, "SUCCESS", reference == null ? List.of() : List.of(reference.evidenceId()), null);
    }
    
    /** 执行该 Agent 运行时组件中的 success 操作。 */
    public void success(ToolInvocation invocation, EvidenceReference reference,Object observedData) {
        success(invocation,reference);saveFactCard(invocation,reference==null?List.of():List.of(reference),observedData);
    }
    
    /** 执行该 Agent 运行时组件中的 success 操作。 */
    public void success(ToolInvocation invocation, List<EvidenceReference> references) {
        evidence.addAll(references);
        finish(invocation, "SUCCESS", references.stream().map(EvidenceReference::evidenceId).toList(), null);
    }
    
    /** 执行该 Agent 运行时组件中的 success 操作。 */
    public void success(ToolInvocation invocation, List<EvidenceReference> references,Object observedData) {
        success(invocation,references);saveFactCard(invocation,references,observedData);
    }

    
    /** 执行该 Agent 运行时组件中的 failure 操作。 */
    public void failure(ToolInvocation invocation, String errorCode) { finish(invocation, "FAILED", List.of(), errorCode); }

    
    /** 执行该 Agent 运行时组件中的 finish 操作。 */
    private void finish(ToolInvocation invocation, String result, List<String> evidenceIds, String errorCode) {
        Instant completed = Instant.now();
        repository.recordToolCall(new AgentToolCallRecord(runId, invocation.toolName(), "fund-tools-v1",
                invocation.argumentHash(), invocation.redactedArguments(), result, evidenceIds, errorCode,
                Duration.between(invocation.startedAt(), completed).toMillis(), invocation.startedAt(), completed));
        Map<String,Object> event=new LinkedHashMap<>();event.put("toolName",invocation.toolName());event.put("result",result);event.put("evidenceIds",evidenceIds);event.put("durationMs",Duration.between(invocation.startedAt(),completed).toMillis());if(errorCode!=null)event.put("errorCode",errorCode);
        events.accept(FundAgentEvent.of("SUCCESS".equals(result)?"tool.completed":"tool.failed",runId,event));
    }

    
    /** 执行该 Agent 运行时组件中的 evidence 操作。 */
    public List<EvidenceReference> evidence() { synchronized(evidence){return List.copyOf(evidence);} }
    
    /** 执行该 Agent 运行时组件中的 seedEvidence 操作。 */
    public void seedEvidence(Collection<EvidenceReference> trustedEvidence){if(trustedEvidence!=null)evidence.addAll(trustedEvidence);}
    
    /** 执行该 Agent 运行时组件中的 toolCalls 操作。 */
    public int toolCalls() { return toolCalls.get(); }
    
    /** 通过 saveFactCard 操作更新持久化或内存中的运行状态。 */
    private void saveFactCard(ToolInvocation invocation,List<EvidenceReference> references,Object observedData){
        if(conversationId==null||conversationId.isBlank()||observedData==null||references.isEmpty())return;
        try{
            String data=mapper.writeValueAsString(observedData);if(data.length()>16000)data=mapper.writeValueAsString(Map.of("truncated",true,"preview",data.substring(0,12000)));
            String subject=references.stream().map(EvidenceReference::fundCode).filter(Objects::nonNull).distinct().reduce((a,b)->a+","+b).orElse(null);
            Instant created=Instant.now();Duration ttl=ttl(invocation.toolName());
            repository.saveFactCard(new com.jijing.fund.agent.api.AgentFactCard(UUID.randomUUID().toString(),conversationId,runId,
                    invocation.toolName(),subject,references,data,created,created.plus(ttl)));
        }catch(RuntimeException ignored){/* Fact memory must never fail the primary tool call. */}
        catch(Exception ignored){/* Serialization failure degrades to no fact card. */}
    }
    
    /** 执行该 Agent 运行时组件中的 ttl 操作。 */
    private Duration ttl(String toolName){String name=toolName.toLowerCase(Locale.ROOT);if(name.contains("realtime"))return Duration.ofMinutes(5);if(name.contains("sector")||name.contains("event")||name.contains("holding")||name.contains("impact")||name.contains("industry"))return Duration.ofHours(1);if(name.contains("document"))return Duration.ofDays(30);if(name.contains("profile"))return Duration.ofDays(7);return defaultFactTtl;}
    
    /** 执行该 Agent 运行时组件中的 safeJson 操作。 */
    private String safeJson(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { return "{}"; } }
    
    /** 构造后续 Agent 处理所需的 sha256 值。 */
    public static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    
    /** 在 Agent 运行时边界间传递 ToolInvocation 数据的不可变值对象。 */
    public record ToolInvocation(String toolName, String redactedArguments, String argumentHash, Instant startedAt) {}
}
