package com.jijing.fund.agent.execution;

import com.jijing.fund.agent.capability.CapabilityExecutionContext;
import com.jijing.fund.agent.capability.CapabilityExecutorRegistry;
import com.jijing.fund.agent.exception.TaskLeaseLostException;
import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 领取就绪任务并在虚拟线程上执行能力，同时由当前线程续租。
 * 租约丢失时取消执行且不发布失败；其他执行失败会在租约仍有效时记为任务失败。
 */
public final class PlanTaskWorker {
    public static final String SCHEMA = "capability-v1";
    private final AgentDagRepository dag;
    private final CapabilityExecutorRegistry executors;

    /**
     * 使用仅供单元测试的通配执行器创建工作者。
     * 存储为空时，第一次领取才会失败。
     */
    public PlanTaskWorker(AgentDagRepository dag) {
        this(dag, CapabilityExecutorRegistry.legacyForUnitTests());
    }

    /**
     * 使用调用方提供的能力注册表创建工作者。
     * 注册表为空时，领取到任务后才会因为找不到执行器而失败。
     */
    public PlanTaskWorker(AgentDagRepository dag, CapabilityExecutorRegistry executors) {
        this.dag = dag;
        this.executors = executors;
    }

    /**
     * 领取一个任务，在可中断的虚拟线程上执行，并由本线程续租。
     * 没有就绪任务时返回空；租约短于 3 毫秒时拒绝。租约丢失会取消执行且不把任务标失败；
     * 无法落库的其他异常会以非法状态抛出。
     */
    public Optional<String> claimAndExecute(String workerId, Instant now, Duration lease) {
        if (lease.toMillis() < 3) {
            throw new IllegalArgumentException("lease must be at least 3ms");
        }
        long started = System.nanoTime();
        Supplier<Instant> current = () -> now.plusNanos(System.nanoTime() - started);
        Optional<ClaimedTask> claimed = dag.claimReady(workerId, current.get(), lease);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedTask task = claimed.get();
        var active = new AtomicBoolean(true);
        Runnable check = () -> {
            if (!active.get() || Thread.currentThread().isInterrupted()) {
                throw new TaskLeaseLostException(task.taskId());
            }
            dag.withLease(task, current.get(), () -> { });
        };
        var context = new CapabilityExecutionContext(
                task,
                check,
                writes -> {
                    check.run();
                    dag.withLease(task, current.get(), writes);
                });
        var future = new FutureTask<Void>(() -> {
            check.run();
            if (AgentCapabilityRegistry.APPROVAL_REQUIRED.contains(task.capabilityType())
                    && !dag.isSideEffectAuthorized(task.taskId())) {
                context.persist(() -> {
                    String approvalId = dag.requestApproval(
                            task.runId(),
                            task.taskId(),
                            task.ownerUserId(),
                            task.capabilityType(),
                            sha(task.inputJson()),
                            "export requires approval",
                            Instant.now().plus(Duration.ofHours(1)),
                            current.get());
                    dag.markWaitingApproval(task, approvalId, current.get());
                });
                return null;
            }
            String key = executionKey(task);
            try {
                if (dag.alreadySucceeded(key)) {
                    dag.completeTask(task, key, "artifact://reused/" + key, List.of("ev-reuse"), current.get());
                } else {
                    check.run();
                    var result = executors.require(task.capabilityType()).execute(context);
                    check.run();
                    dag.completeTask(task, key, result.outputUri(), result.evidenceIds(), current.get());
                }
            } catch (TaskLeaseLostException lost) {
                throw lost;
            } catch (RuntimeException error) {
                check.run();
                String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                dag.failTask(task, reason, current.get());
            }
            return null;
        });
        Thread.ofVirtual().name("plan-task-" + task.taskId()).start(future);
        try {
            while (true) {
                try {
                    future.get(Math.max(1, lease.toMillis() / 3), TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException timeout) {
                    if (!dag.renewLease(task, current.get(), lease)) {
                        break;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException failure) {
            if (!(failure.getCause() instanceof TaskLeaseLostException)) {
                throw new IllegalStateException("task execution could not be persisted", failure.getCause());
            }
        } finally {
            active.set(false);
            future.cancel(true);
        }
        return Optional.of(task.taskId());
    }

    /**
     * 连续领取并执行任务，直到没有任务、达到上限或当前线程被中断。
     * 返回实际执行的次数；单次执行失败落库时会把该次计入并继续，除非异常向外抛出。
     */
    public int drain(String workerId, Instant now, Duration lease, int max) {
        long started = System.nanoTime();
        int n = 0;
        while (n < max
                && !Thread.currentThread().isInterrupted()
                && claimAndExecute(workerId, now.plusNanos(System.nanoTime() - started), lease).isPresent()) {
            n++;
        }
        return n;
    }

    /**
     * 用计划、版本、任务键、输入摘要和能力架构生成幂等键。
     * 摘要计算失败时抛出非法状态，调用方不能把该任务当成已成功。
     */
    public static String executionKey(ClaimedTask task) {
        return sha(task.planId() + "|" + task.planVersion() + "|" + task.taskKey() + "|" + task.inputHash() + "|"
                + SCHEMA);
    }

    /**
     * 计算 UTF-8 文本的 SHA-256 十六进制摘要。
     * 算法不可用时抛出非法状态，不返回截断摘要。
     */
    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
