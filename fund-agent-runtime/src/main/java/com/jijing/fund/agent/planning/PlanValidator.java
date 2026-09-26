package com.jijing.fund.agent.planning;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在计划进入执行前检查目标、预算、任务身份和依赖图。
 * 任一约束失败都抛出 {@link PlanValidationException}，整份计划作废。
 * 路由未选中或审批被拒不是本校验器的职责；对等代理也不得靠追加任务绕过这里的结果。
 */
public final class PlanValidator {

    /**
     * 校验一份计划草稿是否可以绑定到指定运行所有者。
     * 目标为空、任务为空、预算越界、所有者缺失、任务键重复、类型不在白名单、输入含 userId、
     * 缺少证据要求、依赖自身、依赖缺失或依赖成环时抛出 {@link PlanValidationException}，计划不得执行。
     * 任务输入里的用户身份一律拒绝，即使与当前所有者相同，避免草稿在后续复用时被篡改。
     */
    public void validate(PlanDraft draft, String ownerUserId) {
        if (draft == null || draft.goal() == null || draft.goal().isBlank()) {
            throw new PlanValidationException("goal is required");
        }
        if (draft.tasks() == null || draft.tasks().isEmpty()) {
            throw new PlanValidationException("tasks are required");
        }
        int maxTasks = 20;
        if (draft.budget() != null && draft.budget().get("maxTasks") instanceof Number maxTaskBudget) {
            maxTasks = maxTaskBudget.intValue();
        }
        if (maxTasks < 1 || maxTasks > 20) {
            throw new PlanValidationException("maxTasks must be between 1 and 20");
        }
        if (draft.budget() != null && draft.budget().get("maxToolCalls") instanceof Number maxToolCalls
                && (maxToolCalls.intValue() < 1 || maxToolCalls.intValue() > 50)) {
            throw new PlanValidationException("maxToolCalls must be between 1 and 50");
        }
        if (draft.tasks().size() > maxTasks || draft.tasks().size() > 20) {
            throw new PlanValidationException("task budget exceeded");
        }
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new PlanValidationException("owner is required");
        }
        Set<String> keys = new HashSet<>();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (PlanTaskDraft task : draft.tasks()) {
            if (task.taskKey() == null || task.taskKey().isBlank()) {
                throw new PlanValidationException("taskKey is required");
            }
            if (!keys.add(task.taskKey())) {
                throw new PlanValidationException("duplicate taskKey");
            }
            if (!AgentCapabilityRegistry.WHITELIST.contains(task.taskType())) {
                throw new PlanValidationException("unknown task type: " + task.taskType());
            }
            if (task.input() == null) {
                throw new PlanValidationException("input is required");
            }
            // 身份只来自已认证的运行所有者。规划器即使回显当前用户，也会让后续任务复用可以被篡改。
            if (task.input().containsKey("userId")) {
                throw new PlanValidationException("userId is not allowed in task input");
            }
            if (task.evidenceRequirement() == null || task.evidenceRequirement().isEmpty()) {
                throw new PlanValidationException("evidenceRequirement is required");
            }
            edges.put(task.taskKey(), task.dependencies() == null ? List.of() : task.dependencies());
        }
        for (var entry : edges.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (dependency.equals(entry.getKey())) {
                    throw new PlanValidationException("self dependency");
                }
                if (!keys.contains(dependency)) {
                    throw new PlanValidationException("missing dependency: " + dependency);
                }
            }
        }
        if (cyclic(edges)) {
            throw new PlanValidationException("plan contains a cycle");
        }
    }

    /**
     * 判断依赖图是否含环。
     * 发现环时返回 true，由 {@link #validate(PlanDraft, String)} 拒绝整份计划。
     */
    private boolean cyclic(Map<String, List<String>> edges) {
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : edges.keySet()) {
            if (dfs(node, edges, visiting, visited)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从指定任务沿依赖做深度搜索。
     * 再次进入正在访问的节点表示成环，计划必须被拒绝；已完成节点直接跳过。
     */
    private boolean dfs(String node, Map<String, List<String>> edges, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) {
            return true;
        }
        if (visited.contains(node)) {
            return false;
        }
        visiting.add(node);
        for (String next : edges.getOrDefault(node, List.of())) {
            if (dfs(next, edges, visiting, visited)) {
                return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }
}
