package com.jijing.fund.agent.execution;

import com.jijing.fund.agent.api.*;
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

/** 实现 AgentCoordinator 所代表的 Agent 运行时职责。 */
public final class AgentCoordinator implements AgentRunUseCase {
    private final ExecutionModeRouter router;
    private final AgentDagRepository dag;
    private final PlanValidator validator;
    private final RuleBasedPlanner planner;
    private final PlanTaskWorker worker;
    
    /** 执行该 Agent 运行时组件中的 AgentCoordinator 操作。 */
    public AgentCoordinator(ExecutionModeRouter router,AgentDagRepository dag){
        this(router,dag,new PlanTaskWorker(dag));
    }
    
    /** 执行该 Agent 运行时组件中的 AgentCoordinator 操作。 */
    public AgentCoordinator(ExecutionModeRouter router,AgentDagRepository dag,PlanTaskWorker worker){
        this(router,dag,worker,new RuleBasedPlanner());
    }
    
    /** 执行该 Agent 运行时组件中的 AgentCoordinator 操作。 */
    public AgentCoordinator(ExecutionModeRouter router,AgentDagRepository dag,PlanTaskWorker worker,RuleBasedPlanner planner){
        this.router=router;this.dag=dag;this.validator=new PlanValidator();this.planner=planner;this.worker=worker;
    }
    @Override 
    /** 创建并初始化当前 Agent 操作所需的 submit 结果。 */
    public AgentRunView submit(AgentRunCommand command){
        if(command==null||command.message()==null||command.message().isBlank()||command.ownerUserId()==null)throw new AgentInvalidArgumentException("message and owner are required");
        Instant now=Instant.now();
        RouteDecision decision=router.route(command.message(),command.hasPermission());
        String conversationId=command.conversationId()==null||command.conversationId().isBlank()?dag.createConversation(command.ownerUserId(),now):command.conversationId();
        String runId=dag.startRun(conversationId,command.ownerUserId(),command.requestId(),decision.mode().name(),decision.matchedRule(),now);
        dag.saveRoute(runId,command.ownerUserId(),decision,now);
        if(decision.mode()==ExecutionMode.PLAN_AND_EXECUTE){
            PlanDraft draft=planner.draft(command.message());
            validator.validate(draft,command.ownerUserId());
            dag.saveValidatedPlan(runId,command.ownerUserId(),draft,now);
        }else{
            dag.markRunSucceeded(runId,now);
        }
        return dag.requireOwnedRun(runId,command.ownerUserId());
    }
    @Override 
    /** 获取当前 Agent 操作所需的 get 结果。 */
    public AgentRunView get(String runId,String ownerUserId){return dag.requireOwnedRun(runId,ownerUserId);}
    @Override 
    /** 获取当前 Agent 操作所需的 plan 结果。 */
    public AgentPlanView plan(String runId,String ownerUserId){return dag.requireOwnedPlan(runId,ownerUserId);}
    @Override 
    /** 获取当前 Agent 操作所需的 events 结果。 */
    public List<AgentRunEventView> events(String runId,String ownerUserId,Long lastEventId){
        return dag.eventsAfter(runId,ownerUserId,lastEventId==null?0:lastEventId);
    }
    @Override 
    /** 执行 cancel 对应的资源状态转换。 */
    public void cancel(String runId,String ownerUserId){dag.cancelRun(runId,ownerUserId,Instant.now());}
    @Override 
    /** 执行 approve 对应的资源状态转换。 */
    public void approve(String runId,String approvalId,String ownerUserId,String currentParameters){
        dag.requireOwnedRun(runId,ownerUserId);
        var hash=new com.jijing.fund.agent.approval.ApprovalService().hash(currentParameters);
        if(!dag.consumeApproval(approvalId,ownerUserId,hash,Instant.now()))throw new AgentInvalidArgumentException("approval is invalid or parameters changed");
        worker.drain("coordinator",Instant.now(),Duration.ofSeconds(30),50);
    }
    @Override 
    /** 执行 reject 对应的资源状态转换。 */
    public void reject(String runId,String approvalId,String ownerUserId){dag.requireOwnedRun(runId,ownerUserId);dag.rejectApproval(approvalId,ownerUserId,Instant.now());}
    @Override 
    /** 执行 recover 操作，并应用相应的 Agent 运行时状态变化。 */
    public int recover(Instant now){
        return dag.recoverExpiredLeases(now);
    }
}
