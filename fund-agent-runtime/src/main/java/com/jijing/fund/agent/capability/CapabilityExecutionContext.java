package com.jijing.fund.agent.capability;

import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;
import java.util.function.Consumer;

/**
 * 在能力和图执行之间传递任务身份、所有权检查和短持久化动作。
 * 三者缺一不可；没有工作者租约的独立上下文不能写入存储。
 */
public record CapabilityExecutionContext(
        ClaimedTask task,
        Runnable ownershipCheck,
        Consumer<Runnable> persistence) {

    /**
     * 要求任务、所有权检查和持久化动作都存在。
     * 任一为空时抛出非法参数，不创建上下文。
     */
    public CapabilityExecutionContext {
        if (task == null || ownershipCheck == null || persistence == null) {
            throw new IllegalArgumentException("task and lease guards are required");
        }
    }

    /**
     * 给不持有持久租约的单独调用创建一个上下文。
     * 所有权检查为空操作；一旦尝试持久化就会抛出非法状态。
     */
    public CapabilityExecutionContext(ClaimedTask task) {
        this(task, () -> { }, writes -> {
            throw new IllegalStateException("durable writes require a worker lease");
        });
    }

    /**
     * 在每次新的外部操作前检查租约是否仍然有效。
     * 检查动作抛错时调用方必须停止；已经发出的外部请求不一定能取消。
     */
    public void checkActive() {
        ownershipCheck.run();
    }

    /**
     * 把短的检查点或事件写入与租约检查放在一起提交。
     * 不得把外部输入输出放进写入动作；租约失效时由持久化动作拒绝。
     */
    public void persist(Runnable writes) {
        persistence.accept(writes);
    }
}
