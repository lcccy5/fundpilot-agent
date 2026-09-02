package com.jijing.fund.agent.planning;

import java.util.*;

/** 实现 PlanValidator 所代表的 Agent 运行时职责。 */
public final class PlanValidator {
    
    /** 在继续处理前校验 validate 对应的输入或状态。 */
    public void validate(PlanDraft draft,String ownerUserId){
        if(draft==null||draft.goal()==null||draft.goal().isBlank())throw new PlanValidationException("goal is required");
        if(draft.tasks()==null||draft.tasks().isEmpty())throw new PlanValidationException("tasks are required");
        int maxTasks=20;
        if(draft.budget()!=null&&draft.budget().get("maxTasks") instanceof Number n)maxTasks=n.intValue();
        if(maxTasks<1||maxTasks>20)throw new PlanValidationException("maxTasks must be between 1 and 20");
        if(draft.budget()!=null&&draft.budget().get("maxToolCalls") instanceof Number n
                && (n.intValue()<1||n.intValue()>50))throw new PlanValidationException("maxToolCalls must be between 1 and 50");
        if(draft.tasks().size()>maxTasks||draft.tasks().size()>20)throw new PlanValidationException("task budget exceeded");
        if(ownerUserId==null||ownerUserId.isBlank())throw new PlanValidationException("owner is required");
        Set<String> keys=new HashSet<>();
        Map<String,List<String>> edges=new LinkedHashMap<>();
        for(PlanTaskDraft task:draft.tasks()){
            if(task.taskKey()==null||task.taskKey().isBlank())throw new PlanValidationException("taskKey is required");
            if(!keys.add(task.taskKey()))throw new PlanValidationException("duplicate taskKey");
            if(!AgentCapabilityRegistry.WHITELIST.contains(task.taskType()))throw new PlanValidationException("unknown task type: "+task.taskType());
            if(task.input()==null)throw new PlanValidationException("input is required");
            // Identity always comes from the authenticated run owner. Letting a planner
            // echo even the current userId makes later task reuse vulnerable to tampering.
            if(task.input().containsKey("userId"))throw new PlanValidationException("userId is not allowed in task input");
            if(task.evidenceRequirement()==null||task.evidenceRequirement().isEmpty())throw new PlanValidationException("evidenceRequirement is required");
            edges.put(task.taskKey(),task.dependencies()==null?List.of():task.dependencies());
        }
        for(var e:edges.entrySet())for(String dep:e.getValue()){
            if(dep.equals(e.getKey()))throw new PlanValidationException("self dependency");
            if(!keys.contains(dep))throw new PlanValidationException("missing dependency: "+dep);
        }
        if(cyclic(edges))throw new PlanValidationException("plan contains a cycle");
    }
    
    /** 执行该 Agent 运行时组件中的 cyclic 操作。 */
    private boolean cyclic(Map<String,List<String>> edges){
        Set<String> visiting=new HashSet<>(),visited=new HashSet<>();
        for(String node:edges.keySet())if(dfs(node,edges,visiting,visited))return true;
        return false;
    }
    
    /** 执行该 Agent 运行时组件中的 dfs 操作。 */
    private boolean dfs(String node,Map<String,List<String>> edges,Set<String> visiting,Set<String> visited){
        if(visiting.contains(node))return true;if(visited.contains(node))return false;
        visiting.add(node);for(String next:edges.getOrDefault(node,List.of()))if(dfs(next,edges,visiting,visited))return true;
        visiting.remove(node);visited.add(node);return false;
    }
}
