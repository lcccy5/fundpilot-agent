package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.*;
import com.jijing.fund.agent.routing.ExecutionMode;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import com.jijing.fund.agent.routing.RouteDecision;
import java.util.ArrayList;
import java.util.List;

/** Multi-agent is opt-in for Plan-and-Execute only. Supervisor cannot add unvalidated tasks. */
public final class MultiAgentSupervisor {
    private final ExecutionModeRouter router;
    private final PlanValidator validator;
    private final RuleBasedPlanner planner;
    
    /** 执行该 Agent 运行时组件中的 MultiAgentSupervisor 操作。 */
    public MultiAgentSupervisor(ExecutionModeRouter router,PlanValidator validator,RuleBasedPlanner planner){
        this.router=router;this.validator=validator;this.planner=planner;
    }
    
    /** 执行该 Agent 运行时组件中的 decide 操作。 */
    public Assignment decide(String message,boolean hasPermission,boolean multiAgentRequested){
        return decide(message,hasPermission,multiAgentRequested,"supervisor-owner");
    }
    
    /** 执行该 Agent 运行时组件中的 decide 操作。 */
    public Assignment decide(String message,boolean hasPermission,boolean multiAgentRequested,String ownerUserId){
        RouteDecision route=router.route(message,hasPermission);
        if(route.mode()!=ExecutionMode.PLAN_AND_EXECUTE||!multiAgentRequested){
            return new Assignment(false,route,List.of(),null);
        }
        PlanDraft draft=planner.draft(message);
        validator.validate(draft,ownerUserId);
        List<RoleBinding> bindings=new ArrayList<>();
        for(PlanTaskDraft task:draft.tasks()){
            AgentRole role=roleFor(task.taskType());
            if(!RoleToolAcl.allowed(role,task.taskType())&&role==AgentRole.DATA_RESEARCHER){
                role=AgentRole.PORTFOLIO_ANALYST;
            }
            if(!RoleToolAcl.allowed(role,task.taskType())&&role!=AgentRole.DATA_RESEARCHER){
                throw new PlanValidationException("role cannot execute "+task.taskType());
            }
            bindings.add(new RoleBinding(task.taskKey(),role,task.taskType()));
        }
        return new Assignment(true,route,List.copyOf(bindings),draft);
    }
    
    /** 执行 rejectUnvalidatedExtraTask 对应的资源状态转换。 */
    public PlanDraft rejectUnvalidatedExtraTask(PlanDraft validated,PlanTaskDraft extra){
        throw new PlanValidationException("supervisor cannot add tasks outside the validated plan");
    }
    
    /** 执行该 Agent 运行时组件中的 roleFor 操作。 */
    private AgentRole roleFor(String type){
        if(type.startsWith("PORTFOLIO")||"WATCHLIST_READ".equals(type))return AgentRole.PORTFOLIO_ANALYST;
        if("REPORT_VERIFY".equals(type))return AgentRole.VERIFIER;
        if("REPORT_WRITE".equals(type)||"REPORT_EXPORT".equals(type))return AgentRole.WRITER;
        if(type.contains("RISK"))return AgentRole.RISK_ANALYST;
        return AgentRole.DATA_RESEARCHER;
    }
    
    /** 在 Agent 运行时边界间传递 RoleBinding 数据的不可变值对象。 */
    public record RoleBinding(String taskKey,AgentRole role,String capabilityType){}
    
    /** 在 Agent 运行时边界间传递 Assignment 数据的不可变值对象。 */
    public record Assignment(boolean multiAgent,RouteDecision route,List<RoleBinding> bindings,PlanDraft plan){}
}
