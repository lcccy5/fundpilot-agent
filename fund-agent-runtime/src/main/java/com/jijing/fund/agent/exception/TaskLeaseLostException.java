package com.jijing.fund.agent.exception;

/** Signals an expired, cancelled or superseded claim; callers must stop without marking the task failed. */
public final class TaskLeaseLostException extends RuntimeException {
    /** Identifies the task whose execution authorization can no longer be established. */
    public TaskLeaseLostException(String taskId) { super("Task lease lost: " + taskId); }
}
