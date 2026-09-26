package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanTaskDraft;
import com.jijing.fund.agent.planning.PlanValidationException;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionMode;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.routing.RouteDecision;
import java.util.ArrayList;
import java.util.List;

/**
 * 只在计划执行且调用方明确要求时分派对等代理。
 * 监督者不能向已校验计划追加任务。路由没有执行权限时，路由异常直接冒出，不产生分派。
 * 计划校验失败或角色最终仍无权执行时抛出 {@link PlanValidationException}，不返回部分绑定。
 */
public final class MultiAgentSupervisor {
    private final ExecutionModeRouter router;
    private final PlanValidator validator;
    private final RuleBasedPlanner planner;

    /**
     * 绑定路由、计划校验和规则规划器。
     * 这些依赖由调用方保证非空；其中任何一个在分派时失败，本次多代理都不会启动。
     */
    public MultiAgentSupervisor(ExecutionModeRouter router, PlanValidator validator, RuleBasedPlanner planner) {
        this.router = router;
        this.validator = validator;
        this.planner = planner;
    }

    /**
     * 以固定所有者标识分派。
     * 该标识仍须通过计划校验；校验失败时不会退回到无所有者的分派。
     */
    public Assignment decide(String message, boolean hasPermission, boolean multiAgentRequested) {
        return decide(message, hasPermission, multiAgentRequested, "supervisor-owner");
    }

    /**
     * 按路由结果决定是否启动多代理，并给每个任务绑定角色。
     * 路由拒绝权限时异常向外抛出。模式不是计划执行或未请求多代理时，返回未启动的分派，计划为空。
     * 所有者为空、计划不合法，或改派后角色仍不能执行任务时抛出 {@link PlanValidationException}。
     * 数据研究员无权时先改派给组合分析师；其他角色无权则直接失败。
     */
    public Assignment decide(String message, boolean hasPermission, boolean multiAgentRequested, String ownerUserId) {
        RouteDecision route = router.route(message, hasPermission);
        if (route.mode() != ExecutionMode.PLAN_AND_EXECUTE || !multiAgentRequested) {
            return new Assignment(false, route, List.of(), null);
        }
        PlanDraft draft = planner.draft(message);
        validator.validate(draft, ownerUserId);
        List<RoleBinding> bindings = new ArrayList<>();
        for (PlanTaskDraft task : draft.tasks()) {
            AgentRole role = roleFor(task.taskType());
            if (!RoleToolAcl.allowed(role, task.taskType()) && role == AgentRole.DATA_RESEARCHER) {
                role = AgentRole.PORTFOLIO_ANALYST;
            }
            if (!RoleToolAcl.allowed(role, task.taskType()) && role != AgentRole.DATA_RESEARCHER) {
                throw new PlanValidationException("role cannot execute " + task.taskType());
            }
            bindings.add(new RoleBinding(task.taskKey(), role, task.taskType()));
        }
        return new Assignment(true, route, List.copyOf(bindings), draft);
    }

    /**
     * 拒绝把计划外任务附加到已校验草稿。
     * 始终抛出 {@link PlanValidationException}，已校验计划保持原样，对等代理不能借此扩权。
     */
    public PlanDraft rejectUnvalidatedExtraTask(PlanDraft validated, PlanTaskDraft extra) {
        throw new PlanValidationException("supervisor cannot add tasks outside the validated plan");
    }

    /**
     * 按任务类型选择初始角色。
     * 组合和自选归组合分析师，核验归核验者，撰写和导出归撰写者，名称含 RISK 归风险分析师，其余归数据研究员。
     * 初始角色若无权执行，由 {@link #decide} 改派或拒绝，而不是在这里吞掉失败。
     */
    private AgentRole roleFor(String type) {
        if (type.startsWith("PORTFOLIO") || "WATCHLIST_READ".equals(type)) {
            return AgentRole.PORTFOLIO_ANALYST;
        }
        if ("REPORT_VERIFY".equals(type)) {
            return AgentRole.VERIFIER;
        }
        if ("REPORT_WRITE".equals(type) || "REPORT_EXPORT".equals(type)) {
            return AgentRole.WRITER;
        }
        if (type.contains("RISK")) {
            return AgentRole.RISK_ANALYST;
        }
        return AgentRole.DATA_RESEARCHER;
    }

    /**
     * 一个任务与对等角色的绑定。
     * 绑定只在计划校验和访问控制都通过后出现；任一步失败都不会留下半份绑定列表。
     */
    public record RoleBinding(String taskKey, AgentRole role, String capabilityType) {
    }

    /**
     * 监督者的分派结果。
     * {@code multiAgent} 为 false 时绑定为空且计划为 null，表示对等代理没有启动。
     * 路由决策始终保留，便于区分是路由没进入计划执行，还是调用方没有请求多代理。
     */
    public record Assignment(
            boolean multiAgent,
            RouteDecision route,
            List<RoleBinding> bindings,
            PlanDraft plan) {
    }
}
