package com.jijing.fund.agent.planning;

import java.util.List;
import java.util.Map;

/**
 * 一次研究目标及其任务列表的草稿。
 * 预算和输入快照只描述约束，不代表任务已经通过校验或获得审批。
 * 计划为空、含环或超出预算时由校验器整体拒绝；路由失败、审批拒绝或对等代理失败不会改写这份草稿。
 */
public record PlanDraft(
        String goal,
        Map<String, Object> inputSnapshot,
        Map<String, Object> budget,
        List<PlanTaskDraft> tasks) {
}
