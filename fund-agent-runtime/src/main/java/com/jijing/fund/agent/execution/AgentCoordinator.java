package com.jijing.fund.agent.execution;

import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.api.AgentRunEventView;
import com.jijing.fund.agent.api.AgentRunUseCase;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.exception.AgentInvalidArgumentException;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.routing.ExecutionMode;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.routing.RouteDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 计划运行的协调器：校验命令、保存路由和计划，并把查询与审批委托给图存储。
 * 命令不合法或路由不是计划执行时拒绝，不创建运行。
 */
public final class AgentCoordinator implements AgentRunUseCase {
    private final ExecutionModeRouter router;
    private final AgentDagRepository dag;
    private final PlanValidator validator;
    private final RuleBasedPlanner planner;
    private final PlanTaskWorker worker;

    /**
     * 使用默认任务工作者创建协调器。
     * 路由或存储为空时，构造成功，提交时才会失败。
     */
    public AgentCoordinator(ExecutionModeRouter router, AgentDagRepository dag) {
        this(router, dag, new PlanTaskWorker(dag));
    }

    /**
     * 使用调用方提供的任务工作者和默认规则规划器。
     * 工作者为空时，只有审批通过后的继续执行会失败。
     */
    public AgentCoordinator(ExecutionModeRouter router, AgentDagRepository dag, PlanTaskWorker worker) {
        this(router, dag, worker, new RuleBasedPlanner());
    }

    /**
     * 使用调用方提供的任务工作者和规划器。
     * 规划器为空时，提交在通过路由检查后失败，运行可能已经创建。
     */
    public AgentCoordinator(
            ExecutionModeRouter router,
            AgentDagRepository dag,
            PlanTaskWorker worker,
            RuleBasedPlanner planner) {
        this.router = router;
        this.dag = dag;
        this.validator = new PlanValidator();
        this.planner = planner;
        this.worker = worker;
    }

    /**
     * 校验命令后创建计划运行，并保存路由与通过校验的计划。
     * 命令、消息或所有者为空时拒绝且不写存储；没有执行权限或路由不是计划执行时拒绝。
     */
    @Override
    public AgentRunView submit(AgentRunCommand command) {
        if (command == null
                || command.message() == null
                || command.message().isBlank()
                || command.ownerUserId() == null) {
            throw new AgentInvalidArgumentException("message and owner are required");
        }
        Instant now = Instant.now();
        if (!command.hasPermission()) {
            router.route(command.message(), false);
        }
        RouteDecision decision = command.routeDecision() == null
                ? router.route(command.message(), command.hasPermission())
                : command.routeDecision();
        if (decision.mode() != ExecutionMode.PLAN_AND_EXECUTE) {
            throw new AgentInvalidArgumentException("durable runs require PLAN_AND_EXECUTE routing");
        }
        String conversationId = command.conversationId() == null || command.conversationId().isBlank()
                ? dag.createConversation(command.ownerUserId(), now)
                : command.conversationId();
        String runId = dag.startRun(
                conversationId,
                command.ownerUserId(),
                command.requestId(),
                decision.mode().name(),
                decision.matchedRule(),
                now);
        dag.saveRoute(runId, command.ownerUserId(), decision, now);
        PlanDraft draft = planner.draft(command.message());
        validator.validate(draft, command.ownerUserId());
        dag.saveValidatedPlan(runId, command.ownerUserId(), draft, now);
        return dag.requireOwnedRun(runId, command.ownerUserId());
    }

    /**
     * 读取调用方拥有的运行。
     * 运行不存在或所有者不匹配时拒绝。
     */
    @Override
    public AgentRunView get(String runId, String ownerUserId) {
        return dag.requireOwnedRun(runId, ownerUserId);
    }

    /**
     * 读取调用方拥有的计划。
     * 运行不存在、所有者不匹配或计划尚未保存时拒绝。
     */
    @Override
    public AgentPlanView plan(String runId, String ownerUserId) {
        return dag.requireOwnedPlan(runId, ownerUserId);
    }

    /**
     * 增量读取运行事件。
     * 游标为空时从 0 开始；运行不存在或所有者不匹配时拒绝。
     */
    @Override
    public List<AgentRunEventView> events(String runId, String ownerUserId, Long lastEventId) {
        return dag.eventsAfter(runId, ownerUserId, lastEventId == null ? 0 : lastEventId);
    }

    /**
     * 取消调用方拥有的运行。
     * 运行不存在或所有者不匹配时拒绝。
     */
    @Override
    public void cancel(String runId, String ownerUserId) {
        dag.cancelRun(runId, ownerUserId, Instant.now());
    }

    /**
     * 校验运行所有权后核销审批，并继续执行已就绪任务。
     * 运行不存在、审批无效或参数摘要变化时拒绝，不会继续拉取任务。
     */
    @Override
    public void approve(String runId, String approvalId, String ownerUserId, String currentParameters) {
        dag.requireOwnedRun(runId, ownerUserId);
        var hash = new com.jijing.fund.agent.approval.ApprovalService().hash(currentParameters);
        if (!dag.consumeApproval(approvalId, ownerUserId, hash, Instant.now())) {
            throw new AgentInvalidArgumentException("approval is invalid or parameters changed");
        }
        worker.drain("coordinator", Instant.now(), Duration.ofSeconds(30), 50);
    }

    /**
     * 校验运行所有权后拒绝审批。
     * 运行或审批不存在、或不属于该所有者时拒绝。
     */
    @Override
    public void reject(String runId, String approvalId, String ownerUserId) {
        dag.requireOwnedRun(runId, ownerUserId);
        dag.rejectApproval(approvalId, ownerUserId, Instant.now());
    }

    /**
     * 回收给定时刻已经到期的任务租约。
     * 没有到期租约时返回 0。
     */
    @Override
    public int recover(Instant now) {
        return dag.recoverExpiredLeases(now);
    }
}
