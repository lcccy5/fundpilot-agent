package com.jijing.fund.agent.api;

/**
 * 计划中单个任务的对外状态，包含尝试次数和产出地址。
 * 任务尚未成功时产出地址为空；本类型不校验状态枚举是否合法。
 */
public record AgentTaskView(
        String taskId,
        String taskKey,
        String capabilityType,
        String status,
        int attempts,
        String outputUri) {
}
