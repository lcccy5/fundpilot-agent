package com.jijing.fund.agent.api;

import java.util.List;

/**
 * 一次运行所绑定计划的只读视图，包含目标、版本和任务列表。
 * 任务列表为空表示计划没有任务；本类型不复制列表，调用方不应在返回后修改实现持有的列表。
 */
public record AgentPlanView(
        String planId,
        String runId,
        String ownerUserId,
        String status,
        String goal,
        int planVersion,
        List<AgentTaskView> tasks) {
}
