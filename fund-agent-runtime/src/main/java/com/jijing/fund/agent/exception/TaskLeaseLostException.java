package com.jijing.fund.agent.exception;

/**
 * 任务租约已过期、被取消或被新的工作者取代。
 * 调用方必须停止后续写入，且不得把任务标记为失败，以免覆盖后继工作者的结果。
 */
public final class TaskLeaseLostException extends RuntimeException {

    /**
     * 标明无法再证明执行权的任务。
     * 任务标识为空时消息仍会生成，只是无法定位具体任务。
     */
    public TaskLeaseLostException(String taskId) {
        super("Task lease lost: " + taskId);
    }
}
