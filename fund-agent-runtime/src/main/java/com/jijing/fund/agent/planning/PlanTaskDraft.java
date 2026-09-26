package com.jijing.fund.agent.planning;

import java.util.List;
import java.util.Map;

/**
 * 计划中尚未执行的单个任务草稿。
 * 只保存任务键、类型、输入、依赖和证据要求，不调用工具。
 * 计划校验失败时整份草稿被拒绝；路由未选中、审批被拒或对等代理失败时，本对象不会自行降级或补任务。
 */
public record PlanTaskDraft(
        String taskKey,
        String taskType,
        Map<String, Object> input,
        List<String> dependencies,
        List<String> evidenceRequirement) {
}
