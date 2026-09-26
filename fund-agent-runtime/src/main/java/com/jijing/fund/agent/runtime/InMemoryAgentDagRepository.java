package com.jijing.fund.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunEventView;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.exception.TaskLeaseLostException;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanTaskDraft;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.routing.RouteDecision;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的计划运行存储，用一把锁串起领取、完成、失败和租约恢复。
 * 运行不存在或所有者不匹配时拒绝；过期租约上的完成或失败会抛出租约丢失。
 */
public final class InMemoryAgentDagRepository implements AgentDagRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Object lock = new Object();
    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<String, ApprovalState> approvals = new ConcurrentHashMap<>();
    private final Set<String> succeededKeys = ConcurrentHashMap.newKeySet();

    /**
     * 生成一个新的会话标识。
     * 不保存会话，也不校验所有者；重复调用不会返回同一个标识。
     */
    @Override
    public String createConversation(String ownerUserId, Instant now) {
        return UUID.randomUUID().toString();
    }

    /**
     * 创建一条执行中的运行并返回标识。
     * 不校验会话是否存在；所有者为空时之后按所有者读取会失败。
     */
    @Override
    public String startRun(
            String conversationId,
            String ownerUserId,
            String requestId,
            String executionMode,
            String routeReason,
            Instant now) {
        String runId = UUID.randomUUID().toString();
        runs.put(runId, new RunState(
                runId,
                conversationId,
                ownerUserId,
                "RUNNING",
                executionMode,
                routeReason,
                null,
                0,
                new ArrayList<>(),
                now));
        return runId;
    }

    /**
     * 确认所有权后追加一条路由事件。
     * 运行不存在或所有者不匹配时拒绝，不写事件。
     */
    @Override
    public void saveRoute(String runId, String ownerUserId, RouteDecision decision, Instant now) {
        requireOwnedRun(runId, ownerUserId);
        appendEvent(runId, "run.routed",
                "{\"mode\":\"" + decision.mode() + "\",\"rule\":\"" + decision.matchedRule() + "\"}", now);
    }

    /**
     * 保存计划、建立任务，并把无依赖任务标为可领取。
     * 运行不存在或所有者不匹配时拒绝；任务输入无法序列化时失败且不返回计划标识。
     */
    @Override
    public String saveValidatedPlan(String runId, String ownerUserId, PlanDraft draft, Instant now) {
        synchronized (lock) {
            RunState run = owned(runId, ownerUserId);
            String planId = UUID.randomUUID().toString();
            run.planId = planId;
            run.status = "PLAN_RUNNING";
            Map<String, Set<String>> waiting = new HashMap<>();
            for (PlanTaskDraft t : draft.tasks()) {
                String taskId = UUID.randomUUID().toString();
                List<String> deps = t.dependencies() == null ? List.of() : t.dependencies();
                String status = deps.isEmpty() ? "READY" : "PENDING";
                String input = toJson(t.input());
                tasks.put(taskId, new TaskState(
                        taskId,
                        runId,
                        planId,
                        1,
                        t.taskKey(),
                        t.taskType(),
                        status,
                        input,
                        sha(input),
                        0,
                        null,
                        null,
                        null,
                        null,
                        0L,
                        deps));
                waiting.put(t.taskKey(), new HashSet<>(deps));
            }
            run.dependencyIndex = waiting;
            appendUnlocked(run, "plan.created", "{\"planId\":\"" + planId + "\"}", now);
            appendUnlocked(run, "plan.validated", "{\"tasks\":" + draft.tasks().size() + "}", now);
            for (TaskState t : tasks.values()) {
                if (runId.equals(t.runId) && "READY".equals(t.status)) {
                    appendUnlocked(run, "task.ready", "{\"taskKey\":\"" + t.taskKey + "\"}", now);
                }
            }
            return planId;
        }
    }

    /**
     * 按标识查找运行，不检查所有者。
     * 不存在时返回空。
     */
    @Override
    public Optional<AgentRunView> findRun(String runId) {
        RunState r = runs.get(runId);
        return r == null ? Optional.empty() : Optional.of(view(r));
    }

    /**
     * 读取调用方拥有的运行。
     * 运行不存在、所有者为空或不匹配时拒绝。
     */
    @Override
    public AgentRunView requireOwnedRun(String runId, String ownerUserId) {
        return view(owned(runId, ownerUserId));
    }

    /**
     * 读取计划及任务列表。
     * 运行不存在、所有者不匹配或计划尚未写入时拒绝。
     */
    @Override
    public AgentPlanView requireOwnedPlan(String runId, String ownerUserId) {
        RunState run = owned(runId, ownerUserId);
        if (run.planId == null) {
            throw new AgentRunNotFoundException("plan not found");
        }
        List<AgentTaskView> list = tasks.values().stream()
                .filter(t -> runId.equals(t.runId))
                .map(this::taskView)
                .toList();
        return new AgentPlanView(run.planId, runId, run.ownerUserId, run.status, "plan", 1, list);
    }

    /**
     * 返回序号大于游标的事件。
     * 运行不存在或所有者不匹配时拒绝；没有更新事件时返回空列表。
     */
    @Override
    public List<AgentRunEventView> eventsAfter(String runId, String ownerUserId, long lastSequence) {
        RunState run = owned(runId, ownerUserId);
        return run.events.stream().filter(e -> e.sequence() > lastSequence).toList();
    }

    /**
     * 给已存在的运行追加事件。
     * 运行不存在时拒绝；不检查所有者。
     */
    @Override
    public void appendEvent(String runId, String type, String payloadJson, Instant now) {
        synchronized (lock) {
            RunState run = runs.get(runId);
            if (run == null) {
                throw new AgentRunNotFoundException("run not found");
            }
            appendUnlocked(run, type, payloadJson, now);
        }
    }

    /**
     * 领取一条可执行或租约已过期的任务。
     * 没有可领取任务时返回空；租约短于 3 毫秒时拒绝。已取消的运行会被跳过。
     */
    @Override
    public Optional<ClaimedTask> claimReady(String workerId, Instant now, Duration lease) {
        if (lease.toMillis() < 3) {
            throw new IllegalArgumentException("lease must be at least 3ms");
        }
        synchronized (lock) {
            for (TaskState task : tasks.values()) {
                RunState run = runs.get(task.runId);
                if (run == null || "CANCELLED".equals(run.status) || "CANCELLED".equals(task.status)) {
                    continue;
                }
                refreshReady(task);
                if (!"READY".equals(task.status) && !"RUNNING".equals(task.status)) {
                    continue;
                }
                if (task.leaseUntil != null && now.isBefore(task.leaseUntil)) {
                    continue;
                }
                if ("RUNNING".equals(task.status)) {
                    appendUnlocked(run, "task.retrying", toJson(Map.of("taskKey", task.taskKey)), now);
                }
                task.status = "RUNNING";
                task.attempts++;
                task.leaseOwner = workerId;
                task.leaseUntil = now.plus(lease);
                task.leaseVersion++;
                appendUnlocked(run, "task.started",
                        "{\"taskKey\":\"" + task.taskKey + "\",\"attempt\":" + task.attempts + "}", now);
                return Optional.of(new ClaimedTask(
                        task.taskId,
                        task.runId,
                        task.planId,
                        task.planVersion,
                        task.taskKey,
                        task.capabilityType,
                        task.inputJson,
                        task.inputHash,
                        task.attempts,
                        run.ownerUserId,
                        workerId,
                        task.leaseVersion));
            }
            return Optional.empty();
        }
    }

    /**
     * 在当前代际的未过期租约上提交成功，并推进依赖任务。
     * 租约丢失时拒绝；任务已消失、运行已取消或任务已成功时不再改写结果。
     */
    @Override
    public void completeTask(
            ClaimedTask claim,
            String executionKey,
            String outputUri,
            List<String> evidenceIds,
            Instant now) {
        String taskId = claim.taskId();
        synchronized (lock) {
            requireClaim(claim, now);
            TaskState task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            RunState run = runs.get(task.runId);
            if (run == null || "CANCELLED".equals(run.status)) {
                task.status = "CANCELLED";
                return;
            }
            if ("SUCCEEDED".equals(task.status)) {
                return;
            }
            task.status = "SUCCEEDED";
            task.outputUri = outputUri;
            task.leaseUntil = null;
            succeededKeys.add(executionKey);
            appendUnlocked(run, "task.completed",
                    "{\"taskKey\":\"" + task.taskKey + "\",\"outputUri\":\"" + outputUri + "\"}", now);
            if ("REPORT_VERIFY".equals(task.capabilityType)) {
                appendUnlocked(run, "verification.completed", "{\"ok\":true}", now);
            }
            if ("REPORT_WRITE".equals(task.capabilityType)) {
                appendUnlocked(run, "report.completed", "{\"uri\":\"" + outputUri + "\"}", now);
            }
            promoteDependents(run, task.taskKey, now);
            if (allTerminal(run.runId) && tasks.values().stream()
                    .noneMatch(t -> run.runId.equals(t.runId) && "WAITING_APPROVAL".equals(t.status))) {
                run.status = "SUCCEEDED";
                appendUnlocked(run, "run.completed", "{\"runId\":\"" + run.runId + "\"}", now);
            }
        }
    }

    /**
     * 把任务标为失败，取消同运行中其余未成功任务，并发布失败事件。
     * 租约丢失时拒绝且不改状态；原因为空时事件里使用固定的失败说明。
     */
    @Override
    public void failTask(ClaimedTask claim, String reason, Instant now) {
        String taskId = claim.taskId();
        synchronized (lock) {
            requireClaim(claim, now);
            TaskState task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            RunState run = runs.get(task.runId);
            if (run == null || "CANCELLED".equals(run.status)) {
                return;
            }
            task.status = "FAILED";
            task.leaseUntil = null;
            for (TaskState candidate : tasks.values()) {
                if (run.runId.equals(candidate.runId)
                        && !"SUCCEEDED".equals(candidate.status)
                        && !candidate.taskId.equals(taskId)) {
                    candidate.status = "CANCELLED";
                }
            }
            run.status = "FAILED";
            String detail = reason == null || reason.isBlank()
                    ? "任务执行失败"
                    : reason.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
            appendUnlocked(run, "task.failed",
                    "{\"taskKey\":\"" + task.taskKey + "\",\"reason\":\"" + detail + "\"}", now);
            appendUnlocked(run, "run.failed",
                    "{\"runId\":\"" + run.runId + "\",\"reason\":\"" + detail + "\"}", now);
        }
    }

    /**
     * 在当前租约仍有效时把任务改成等待审批并释放租约。
     * 租约丢失时拒绝；任务已消失时返回。运行记录缺失时会在更新状态处失败。
     */
    @Override
    public void markWaitingApproval(ClaimedTask claim, String approvalId, Instant now) {
        String taskId = claim.taskId();
        synchronized (lock) {
            requireClaim(claim, now);
            TaskState task = tasks.get(taskId);
            if (task == null) {
                return;
            }
            task.status = "WAITING_APPROVAL";
            task.leaseUntil = null;
            RunState run = runs.get(task.runId);
            run.status = "WAITING_APPROVAL";
            appendUnlocked(run, "approval.requested",
                    "{\"approvalId\":\"" + approvalId + "\",\"taskKey\":\"" + task.taskKey + "\"}", now);
        }
    }

    /**
     * 把尚未成功且未取消的任务改回可领取。
     * 任务不存在时静默返回。
     */
    @Override
    public void markTaskReady(String taskId) {
        synchronized (lock) {
            TaskState task = tasks.get(taskId);
            if (task != null && !"SUCCEEDED".equals(task.status) && !"CANCELLED".equals(task.status)) {
                task.status = "READY";
            }
        }
    }

    /**
     * 取消运行及其尚未成功的任务。
     * 运行不存在或所有者不匹配时拒绝。
     */
    @Override
    public void cancelRun(String runId, String ownerUserId, Instant now) {
        synchronized (lock) {
            RunState run = owned(runId, ownerUserId);
            run.status = "CANCELLED";
            for (TaskState t : tasks.values()) {
                if (runId.equals(t.runId) && !"SUCCEEDED".equals(t.status)) {
                    t.status = "CANCELLED";
                }
            }
            appendUnlocked(run, "run.cancelled", "{\"runId\":\"" + runId + "\"}", now);
        }
    }

    /**
     * 在确认运行所有权后登记一条待核销审批。
     * 运行不存在或所有者不匹配时拒绝，不创建审批。
     */
    @Override
    public String requestApproval(
            String runId,
            String taskId,
            String ownerUserId,
            String actionType,
            String parameterHash,
            String summary,
            Instant expiresAt,
            Instant now) {
        synchronized (lock) {
            owned(runId, ownerUserId);
            String id = UUID.randomUUID().toString();
            approvals.put(id, new ApprovalState(
                    id, runId, taskId, ownerUserId, actionType, parameterHash, "PENDING", expiresAt, null));
            return id;
        }
    }

    /**
     * 在未过期且参数摘要一致时核销审批，并把任务重新标为可领取。
     * 审批缺失、所有者不匹配、已使用、已过期或摘要不一致时返回 false。
     */
    @Override
    public boolean consumeApproval(String approvalId, String ownerUserId, String expectedHash, Instant now) {
        synchronized (lock) {
            ApprovalState a = approvals.get(approvalId);
            if (a == null || !a.ownerUserId.equals(ownerUserId) || a.usedAt != null) {
                return false;
            }
            if (now.isAfter(a.expiresAt) || !Objects.equals(a.parameterHash, expectedHash)) {
                return false;
            }
            a.usedAt = now;
            a.status = "APPROVED";
            TaskState task = tasks.get(a.taskId);
            if (task != null) {
                task.status = "READY";
                task.authorized = true;
            }
            RunState run = runs.get(a.runId);
            run.status = "PLAN_RUNNING";
            appendUnlocked(run, "approval.resolved",
                    "{\"approvalId\":\"" + approvalId + "\",\"status\":\"APPROVED\"}", now);
            return true;
        }
    }

    /**
     * 拒绝审批，取消关联任务和运行。
     * 审批不存在或所有者不匹配时拒绝。
     */
    @Override
    public void rejectApproval(String approvalId, String ownerUserId, Instant now) {
        synchronized (lock) {
            ApprovalState a = approvals.get(approvalId);
            if (a == null || !a.ownerUserId.equals(ownerUserId)) {
                throw new AgentRunNotFoundException("approval not found");
            }
            a.status = "REJECTED";
            a.usedAt = now;
            TaskState task = tasks.get(a.taskId);
            if (task != null) {
                task.status = "CANCELLED";
            }
            RunState run = runs.get(a.runId);
            run.status = "CANCELLED";
            appendUnlocked(run, "approval.resolved",
                    "{\"approvalId\":\"" + approvalId + "\",\"status\":\"REJECTED\"}", now);
        }
    }

    /**
     * 把到期仍在执行中的任务恢复为可领取。
     * 没有这样的任务时返回 0；运行记录缺失时追加事件会失败。
     */
    @Override
    public int recoverExpiredLeases(Instant now) {
        synchronized (lock) {
            int n = 0;
            for (TaskState t : tasks.values()) {
                if ("RUNNING".equals(t.status)
                        && t.leaseUntil != null
                        && !now.isBefore(t.leaseUntil)
                        && !"SUCCEEDED".equals(t.status)) {
                    t.status = "READY";
                    t.leaseUntil = null;
                    n++;
                    appendUnlocked(runs.get(t.runId), "task.retrying", "{\"taskKey\":\"" + t.taskKey + "\"}", now);
                }
            }
            return n;
        }
    }

    /**
     * 判断幂等键是否已经成功执行过。
     * 未见过时返回 false。
     */
    @Override
    public boolean alreadySucceeded(String executionKey) {
        return succeededKeys.contains(executionKey);
    }

    /**
     * 判断任务是否已获得副作用授权。
     * 任务不存在时返回 false。
     */
    @Override
    public boolean isSideEffectAuthorized(String taskId) {
        TaskState t = tasks.get(taskId);
        return t != null && t.authorized;
    }

    /**
     * 把已存在的运行标成成功。
     * 运行不存在时静默返回。
     */
    @Override
    public void markRunSucceeded(String runId, Instant now) {
        synchronized (lock) {
            RunState run = runs.get(runId);
            if (run == null) {
                return;
            }
            run.status = "SUCCEEDED";
            appendUnlocked(run, "run.completed", "{\"runId\":\"" + runId + "\"}", now);
        }
    }

    /**
     * 在与领取、恢复同一把锁下校验租约代际、工作者和截止时间。
     * 任务不存在、不在执行中、工作者或代际不匹配、或当前时间已不早于截止时间时抛出租约丢失。
     */
    private void requireClaim(ClaimedTask claim, Instant now) {
        TaskState task = tasks.get(claim.taskId());
        if (task == null
                || !"RUNNING".equals(task.status)
                || !Objects.equals(task.leaseOwner, claim.workerId())
                || task.leaseVersion != claim.leaseVersion()
                || task.leaseUntil == null
                || !now.isBefore(task.leaseUntil)) {
            throw new TaskLeaseLostException(claim.taskId());
        }
    }

    /**
     * 在租约仍然有效时执行短写入，使内存检查点相对接管是原子的。
     * 租约丢失时拒绝，写入动作不会执行。
     */
    @Override
    public void withLease(ClaimedTask claim, Instant now, Runnable writes) {
        synchronized (lock) {
            requireClaim(claim, now);
            writes.run();
        }
    }

    /**
     * 延长未过期代际的截止时间，不改变隔离令牌。
     * 租约已丢失时返回 false；新租约短于 3 毫秒时拒绝。
     */
    @Override
    public boolean renewLease(ClaimedTask claim, Instant now, Duration lease) {
        synchronized (lock) {
            if (lease.toMillis() < 3) {
                throw new IllegalArgumentException("lease must be at least 3ms");
            }
            try {
                requireClaim(claim, now);
            } catch (TaskLeaseLostException lost) {
                return false;
            }
            tasks.get(claim.taskId()).leaseUntil = now.plus(lease);
            return true;
        }
    }

    /**
     * 当待执行任务的依赖都已成功时，把它改成可领取。
     * 任务不是待执行状态时不做任何事。
     */
    private void refreshReady(TaskState task) {
        if (!"PENDING".equals(task.status)) {
            return;
        }
        boolean ready = task.dependencies.stream().allMatch(dep -> tasks.values().stream()
                .anyMatch(o -> task.planId.equals(o.planId) && dep.equals(o.taskKey) && "SUCCEEDED".equals(o.status)));
        if (ready) {
            task.status = "READY";
        }
    }

    /**
     * 在某个任务成功后，把依赖它且已经齐备的任务标为可领取并记事件。
     * 没有可推进的任务时不写事件。
     */
    private void promoteDependents(RunState run, String completedKey, Instant now) {
        for (TaskState t : tasks.values()) {
            if (!run.runId.equals(t.runId) || !"PENDING".equals(t.status)) {
                continue;
            }
            refreshReady(t);
            if ("READY".equals(t.status)) {
                appendUnlocked(run, "task.ready", "{\"taskKey\":\"" + t.taskKey + "\"}", now);
            }
        }
    }

    /**
     * 判断运行中的任务是否都已进入终态或等待审批。
     * 没有任务时返回 true。
     */
    private boolean allTerminal(String runId) {
        return tasks.values().stream()
                .filter(t -> runId.equals(t.runId))
                .allMatch(t -> "SUCCEEDED".equals(t.status)
                        || "CANCELLED".equals(t.status)
                        || "SKIPPED".equals(t.status)
                        || "WAITING_APPROVAL".equals(t.status));
    }

    /**
     * 取出调用方拥有的运行记录。
     * 运行不存在、所有者为空或不匹配时拒绝，且不说明是哪一种。
     */
    private RunState owned(String runId, String ownerUserId) {
        RunState run = runs.get(runId);
        if (run == null || ownerUserId == null || !ownerUserId.equals(run.ownerUserId)) {
            throw new AgentRunNotFoundException("run not found");
        }
        return run;
    }

    /**
     * 在已持有锁时追加事件并推进序号。
     * 运行记录为空时失败；不检查载荷是否为合法 JSON。
     */
    private void appendUnlocked(RunState run, String type, String payload, Instant now) {
        run.lastEventSequence++;
        run.events.add(new AgentRunEventView(UUID.randomUUID().toString(), run.lastEventSequence, type, payload, now));
    }

    /**
     * 把内部运行记录收成对外快照。
     * 不会失败。
     */
    private AgentRunView view(RunState r) {
        return new AgentRunView(
                r.runId,
                r.conversationId,
                r.ownerUserId,
                r.status,
                r.executionMode,
                r.routeReason,
                r.planId,
                r.lastEventSequence);
    }

    /**
     * 把内部任务记录收成对外快照。
     * 不会失败。
     */
    private AgentTaskView taskView(TaskState t) {
        return new AgentTaskView(t.taskId, t.taskKey, t.capabilityType, t.status, t.attempts, t.outputUri);
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     * 算法不可用时抛出非法状态。
     */
    private String sha(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 把任务输入序列化成 JSON；空值写成空对象。
     * 无法序列化时抛出非法参数，调用方不应继续保存计划。
     */
    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalArgumentException("task input must be JSON serializable", e);
        }
    }

    /**
     * 一次运行的可变内部状态，包括计划标识和事件日志。
     * 构造参数中的时间不被保存，不能用来判断运行是否过期。
     */
    private static final class RunState {
        final String runId;
        final String conversationId;
        final String ownerUserId;
        final String executionMode;
        final String routeReason;
        String status;
        String planId;
        long lastEventSequence;
        final List<AgentRunEventView> events;
        Map<String, Set<String>> dependencyIndex = Map.of();

        /**
         * 创建运行状态。时间参数被忽略，不参与后续判断。
         * 事件列表由调用方持有，后续追加会修改同一列表。
         */
        RunState(
                String runId,
                String conversationId,
                String ownerUserId,
                String status,
                String executionMode,
                String routeReason,
                String planId,
                long seq,
                List<AgentRunEventView> events,
                Instant ignored) {
            this.runId = runId;
            this.conversationId = conversationId;
            this.ownerUserId = ownerUserId;
            this.status = status;
            this.executionMode = executionMode;
            this.routeReason = routeReason;
            this.planId = planId;
            this.lastEventSequence = seq;
            this.events = events;
        }
    }

    /**
     * 计划中一个任务的可变内部状态，包括租约所有者和代际。
     * 依赖列表由调用方提供，本类不复制。
     */
    private static final class TaskState {
        final String taskId;
        final String runId;
        final String planId;
        final int planVersion;
        final String taskKey;
        final String capabilityType;
        final String inputJson;
        final String inputHash;
        final List<String> dependencies;
        String status;
        String outputUri;
        String leaseOwner;
        Instant leaseUntil;
        int attempts;
        long leaseVersion;
        boolean authorized;

        /**
         * 创建任务状态。被忽略的包装类型参数不参与租约判断。
         * 不校验依赖是否指向真实任务。
         */
        TaskState(
                String taskId,
                String runId,
                String planId,
                int planVersion,
                String taskKey,
                String capabilityType,
                String status,
                String inputJson,
                String inputHash,
                int attempts,
                String outputUri,
                String leaseOwner,
                Instant leaseUntil,
                Long ignored,
                long leaseVersion,
                List<String> dependencies) {
            this.taskId = taskId;
            this.runId = runId;
            this.planId = planId;
            this.planVersion = planVersion;
            this.taskKey = taskKey;
            this.capabilityType = capabilityType;
            this.status = status;
            this.inputJson = inputJson;
            this.inputHash = inputHash;
            this.attempts = attempts;
            this.outputUri = outputUri;
            this.leaseOwner = leaseOwner;
            this.leaseUntil = leaseUntil;
            this.leaseVersion = leaseVersion;
            this.dependencies = dependencies;
        }
    }

    /**
     * 一条审批的内部状态，包含参数摘要和过期时间。
     * 已使用时间不为空表示不能再次核销。
     */
    private static final class ApprovalState {
        final String approvalId;
        final String runId;
        final String taskId;
        final String ownerUserId;
        final String actionType;
        final String parameterHash;
        String status;
        final Instant expiresAt;
        Instant usedAt;

        /**
         * 创建审批状态。
         * 不校验过期时间是否已过，核销时才比较。
         */
        ApprovalState(
                String approvalId,
                String runId,
                String taskId,
                String ownerUserId,
                String actionType,
                String parameterHash,
                String status,
                Instant expiresAt,
                Instant usedAt) {
            this.approvalId = approvalId;
            this.runId = runId;
            this.taskId = taskId;
            this.ownerUserId = ownerUserId;
            this.actionType = actionType;
            this.parameterHash = parameterHash;
            this.status = status;
            this.expiresAt = expiresAt;
            this.usedAt = usedAt;
        }
    }
}
