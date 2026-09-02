package com.jijing.fund.agent.runtime;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.planning.*;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.routing.RouteDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 实现 InMemoryAgentDagRepository 所代表的 Agent 运行时职责。 */
public final class InMemoryAgentDagRepository implements AgentDagRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Object lock=new Object();
    private final Map<String,RunState> runs=new ConcurrentHashMap<>();
    private final Map<String,TaskState> tasks=new ConcurrentHashMap<>();
    private final Map<String,ApprovalState> approvals=new ConcurrentHashMap<>();
    private final Set<String> succeededKeys=ConcurrentHashMap.newKeySet();

    @Override 
    /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
    public String createConversation(String ownerUserId,Instant now){return UUID.randomUUID().toString();}

    @Override 
    /** 创建并初始化当前 Agent 操作所需的 startRun 结果。 */
    public String startRun(String conversationId,String ownerUserId,String requestId,String executionMode,String routeReason,Instant now){
        String runId=UUID.randomUUID().toString();
        runs.put(runId,new RunState(runId,conversationId,ownerUserId,"RUNNING",executionMode,routeReason,null,0,new ArrayList<>(),now));
        return runId;
    }

    @Override 
    /** 通过 saveRoute 操作更新持久化或内存中的运行状态。 */
    public void saveRoute(String runId,String ownerUserId,RouteDecision decision,Instant now){
        requireOwnedRun(runId,ownerUserId);
        appendEvent(runId,"run.routed","{\"mode\":\""+decision.mode()+"\",\"rule\":\""+decision.matchedRule()+"\"}",now);
    }

    @Override 
    /** 通过 saveValidatedPlan 操作更新持久化或内存中的运行状态。 */
    public String saveValidatedPlan(String runId,String ownerUserId,PlanDraft draft,Instant now){
        synchronized(lock){
            RunState run=owned(runId,ownerUserId);
            String planId=UUID.randomUUID().toString();
            run.planId=planId;run.status="PLAN_RUNNING";
            Map<String,Set<String>> waiting=new HashMap<>();
            for(PlanTaskDraft t:draft.tasks()){
                String taskId=UUID.randomUUID().toString();
                List<String> deps=t.dependencies()==null?List.of():t.dependencies();
                String status=deps.isEmpty()?"READY":"PENDING";
                String input=toJson(t.input());
                tasks.put(taskId,new TaskState(taskId,runId,planId,1,t.taskKey(),t.taskType(),status,input,sha(input),0,null,null,null,null,0L,deps));
                waiting.put(t.taskKey(),new HashSet<>(deps));
            }
            run.dependencyIndex=waiting;
            appendUnlocked(run,"plan.created","{\"planId\":\""+planId+"\"}",now);
            appendUnlocked(run,"plan.validated","{\"tasks\":"+draft.tasks().size()+"}",now);
            for(TaskState t:tasks.values())if(runId.equals(t.runId)&&"READY".equals(t.status))appendUnlocked(run,"task.ready","{\"taskKey\":\""+t.taskKey+"\"}",now);
            return planId;
        }
    }

    @Override 
    /** 获取当前 Agent 操作所需的 findRun 结果。 */
    public Optional<AgentRunView> findRun(String runId){RunState r=runs.get(runId);return r==null?Optional.empty():Optional.of(view(r));}
    @Override 
    /** 执行该 Agent 运行时组件中的 requireOwnedRun 操作。 */
    public AgentRunView requireOwnedRun(String runId,String ownerUserId){return view(owned(runId,ownerUserId));}
    @Override 
    /** 执行该 Agent 运行时组件中的 requireOwnedPlan 操作。 */
    public AgentPlanView requireOwnedPlan(String runId,String ownerUserId){
        RunState run=owned(runId,ownerUserId);
        if(run.planId==null)throw new AgentRunNotFoundException("plan not found");
        List<AgentTaskView> list=tasks.values().stream().filter(t->runId.equals(t.runId)).map(this::taskView).toList();
        return new AgentPlanView(run.planId,runId,run.ownerUserId,run.status,"plan",1,list);
    }
    @Override 
    /** 获取当前 Agent 操作所需的 eventsAfter 结果。 */
    public List<AgentRunEventView> eventsAfter(String runId,String ownerUserId,long lastSequence){
        RunState run=owned(runId,ownerUserId);
        return run.events.stream().filter(e->e.sequence()>lastSequence).toList();
    }
    @Override 
    /** 通过 appendEvent 操作更新持久化或内存中的运行状态。 */
    public void appendEvent(String runId,String type,String payloadJson,Instant now){
        synchronized(lock){RunState run=runs.get(runId);if(run==null)throw new AgentRunNotFoundException("run not found");appendUnlocked(run,type,payloadJson,now);}
    }

    @Override 
    /** 通过 claimReady 操作更新持久化或内存中的运行状态。 */
    public Optional<ClaimedTask> claimReady(String workerId,Instant now,Duration lease){
        synchronized(lock){
            for(TaskState task:tasks.values()){
                RunState run=runs.get(task.runId);
                if(run==null||"CANCELLED".equals(run.status)||"CANCELLED".equals(task.status))continue;
                refreshReady(task);
                if(!"READY".equals(task.status))continue;
                if(task.leaseUntil!=null&&now.isBefore(task.leaseUntil))continue;
                task.status="RUNNING";task.attempts++;task.leaseOwner=workerId;task.leaseUntil=now.plus(lease);task.leaseVersion++;
                appendUnlocked(run,"task.started","{\"taskKey\":\""+task.taskKey+"\",\"attempt\":"+task.attempts+"}",now);
                return Optional.of(new ClaimedTask(task.taskId,task.runId,task.planId,task.planVersion,task.taskKey,task.capabilityType,task.inputJson,task.inputHash,task.attempts,run.ownerUserId));
            }
            return Optional.empty();
        }
    }

    @Override 
    /** 通过 completeTask 操作更新持久化或内存中的运行状态。 */
    public void completeTask(String taskId,String executionKey,String outputUri,List<String> evidenceIds,Instant now){
        synchronized(lock){
            TaskState task=tasks.get(taskId);if(task==null)return;
            RunState run=runs.get(task.runId);if(run==null||"CANCELLED".equals(run.status)){task.status="CANCELLED";return;}
            if("SUCCEEDED".equals(task.status))return;
            task.status="SUCCEEDED";task.outputUri=outputUri;task.leaseUntil=null;succeededKeys.add(executionKey);
            appendUnlocked(run,"task.completed","{\"taskKey\":\""+task.taskKey+"\",\"outputUri\":\""+outputUri+"\"}",now);
            if("REPORT_VERIFY".equals(task.capabilityType))appendUnlocked(run,"verification.completed","{\"ok\":true}",now);
            if("REPORT_WRITE".equals(task.capabilityType))appendUnlocked(run,"report.completed","{\"uri\":\""+outputUri+"\"}",now);
            promoteDependents(run,task.taskKey,now);
            if(allTerminal(run.runId)&&tasks.values().stream().noneMatch(t->run.runId.equals(t.runId)&&"WAITING_APPROVAL".equals(t.status))){
                run.status="SUCCEEDED";appendUnlocked(run,"run.completed","{\"runId\":\""+run.runId+"\"}",now);
            }
        }
    }

    @Override 
    /** 通过 markWaitingApproval 操作更新持久化或内存中的运行状态。 */
    public void markWaitingApproval(String taskId,String approvalId,Instant now){
        synchronized(lock){
            TaskState task=tasks.get(taskId);if(task==null)return;
            task.status="WAITING_APPROVAL";task.leaseUntil=null;
            RunState run=runs.get(task.runId);run.status="WAITING_APPROVAL";
            appendUnlocked(run,"approval.requested","{\"approvalId\":\""+approvalId+"\",\"taskKey\":\""+task.taskKey+"\"}",now);
        }
    }

    @Override 
    /** 通过 markTaskReady 操作更新持久化或内存中的运行状态。 */
    public void markTaskReady(String taskId){
        synchronized(lock){TaskState task=tasks.get(taskId);if(task!=null&&!"SUCCEEDED".equals(task.status)&&!"CANCELLED".equals(task.status))task.status="READY";}
    }

    @Override 
    /** 执行 cancelRun 对应的资源状态转换。 */
    public void cancelRun(String runId,String ownerUserId,Instant now){
        synchronized(lock){
            RunState run=owned(runId,ownerUserId);
            run.status="CANCELLED";
            for(TaskState t:tasks.values())if(runId.equals(t.runId)&&!"SUCCEEDED".equals(t.status))t.status="CANCELLED";
            appendUnlocked(run,"run.cancelled","{\"runId\":\""+runId+"\"}",now);
        }
    }

    @Override 
    /** 执行该 Agent 运行时组件中的 requestApproval 操作。 */
    public String requestApproval(String runId,String taskId,String ownerUserId,String actionType,String parameterHash,String summary,Instant expiresAt,Instant now){
        synchronized(lock){
            owned(runId,ownerUserId);
            String id=UUID.randomUUID().toString();
            approvals.put(id,new ApprovalState(id,runId,taskId,ownerUserId,actionType,parameterHash,"PENDING",expiresAt,null));
            return id;
        }
    }

    @Override 
    /** 执行 consumeApproval 操作，并应用相应的 Agent 运行时状态变化。 */
    public boolean consumeApproval(String approvalId,String ownerUserId,String expectedHash,Instant now){
        synchronized(lock){
            ApprovalState a=approvals.get(approvalId);
            if(a==null||!a.ownerUserId.equals(ownerUserId)||a.usedAt!=null)return false;
            if(now.isAfter(a.expiresAt)||!Objects.equals(a.parameterHash,expectedHash))return false;
            a.usedAt=now;a.status="APPROVED";
            TaskState task=tasks.get(a.taskId);if(task!=null){task.status="READY";task.authorized=true;}
            RunState run=runs.get(a.runId);run.status="PLAN_RUNNING";
            appendUnlocked(run,"approval.resolved","{\"approvalId\":\""+approvalId+"\",\"status\":\"APPROVED\"}",now);
            return true;
        }
    }

    @Override 
    /** 执行 rejectApproval 对应的资源状态转换。 */
    public void rejectApproval(String approvalId,String ownerUserId,Instant now){
        synchronized(lock){
            ApprovalState a=approvals.get(approvalId);if(a==null||!a.ownerUserId.equals(ownerUserId))throw new AgentRunNotFoundException("approval not found");
            a.status="REJECTED";a.usedAt=now;
            TaskState task=tasks.get(a.taskId);if(task!=null)task.status="CANCELLED";
            RunState run=runs.get(a.runId);run.status="CANCELLED";
            appendUnlocked(run,"approval.resolved","{\"approvalId\":\""+approvalId+"\",\"status\":\"REJECTED\"}",now);
        }
    }

    @Override 
    /** 执行 recoverExpiredLeases 操作，并应用相应的 Agent 运行时状态变化。 */
    public int recoverExpiredLeases(Instant now){
        synchronized(lock){
            int n=0;
            for(TaskState t:tasks.values()){
                if("RUNNING".equals(t.status)&&t.leaseUntil!=null&&!now.isBefore(t.leaseUntil)&&!"SUCCEEDED".equals(t.status)){
                    t.status="READY";t.leaseUntil=null;n++;
                    appendUnlocked(runs.get(t.runId),"task.retrying","{\"taskKey\":\""+t.taskKey+"\"}",now);
                }
            }
            return n;
        }
    }

    @Override 
    /** 执行该 Agent 运行时组件中的 alreadySucceeded 操作。 */
    public boolean alreadySucceeded(String executionKey){return succeededKeys.contains(executionKey);}
    @Override 
    /** 判断 isSideEffectAuthorized 对应的条件是否成立。 */
    public boolean isSideEffectAuthorized(String taskId){
        TaskState t=tasks.get(taskId);return t!=null&&t.authorized;
    }
    @Override 
    /** 通过 markRunSucceeded 操作更新持久化或内存中的运行状态。 */
    public void markRunSucceeded(String runId,Instant now){
        synchronized(lock){RunState run=runs.get(runId);if(run==null)return;run.status="SUCCEEDED";appendUnlocked(run,"run.completed","{\"runId\":\""+runId+"\"}",now);}
    }

    
    /** 通过 refreshReady 操作更新持久化或内存中的运行状态。 */
    private void refreshReady(TaskState task){
        if(!"PENDING".equals(task.status))return;
        boolean ready=task.dependencies.stream().allMatch(dep->tasks.values().stream().anyMatch(o->task.planId.equals(o.planId)&&dep.equals(o.taskKey)&&"SUCCEEDED".equals(o.status)));
        if(ready)task.status="READY";
    }
    
    /** 通过 promoteDependents 操作更新持久化或内存中的运行状态。 */
    private void promoteDependents(RunState run,String completedKey,Instant now){
        for(TaskState t:tasks.values()){
            if(!run.runId.equals(t.runId)||!"PENDING".equals(t.status))continue;
            refreshReady(t);
            if("READY".equals(t.status))appendUnlocked(run,"task.ready","{\"taskKey\":\""+t.taskKey+"\"}",now);
        }
    }
    
    /** 执行该 Agent 运行时组件中的 allTerminal 操作。 */
    private boolean allTerminal(String runId){
        return tasks.values().stream().filter(t->runId.equals(t.runId)).allMatch(t->"SUCCEEDED".equals(t.status)||"CANCELLED".equals(t.status)||"SKIPPED".equals(t.status)||"WAITING_APPROVAL".equals(t.status));
    }
    
    /** 执行该 Agent 运行时组件中的 owned 操作。 */
    private RunState owned(String runId,String ownerUserId){
        RunState run=runs.get(runId);
        if(run==null||ownerUserId==null||!ownerUserId.equals(run.ownerUserId))throw new AgentRunNotFoundException("run not found");
        return run;
    }
    
    /** 通过 appendUnlocked 操作更新持久化或内存中的运行状态。 */
    private void appendUnlocked(RunState run,String type,String payload,Instant now){
        run.lastEventSequence++;
        run.events.add(new AgentRunEventView(UUID.randomUUID().toString(),run.lastEventSequence,type,payload,now));
    }
    
    /** 获取当前 Agent 操作所需的 view 结果。 */
    private AgentRunView view(RunState r){return new AgentRunView(r.runId,r.conversationId,r.ownerUserId,r.status,r.executionMode,r.routeReason,r.planId,r.lastEventSequence);}
    
    /** 执行该 Agent 运行时组件中的 taskView 操作。 */
    private AgentTaskView taskView(TaskState t){return new AgentTaskView(t.taskId,t.taskKey,t.capabilityType,t.status,t.attempts,t.outputUri);}
    
    /** 构造后续 Agent 处理所需的 sha 值。 */
    private String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    
    /** 构造后续 Agent 处理所需的 toJson 值。 */
    private String toJson(Object value){
        try{return JSON.writeValueAsString(value==null?Map.of():value);}
        catch(Exception e){throw new IllegalArgumentException("task input must be JSON serializable",e);}
    }

    
    /** 实现 RunState 所代表的 Agent 运行时职责。 */
    private static final class RunState {
        final String runId,conversationId,ownerUserId,executionMode,routeReason;
        String status,planId;long lastEventSequence;final List<AgentRunEventView> events;Map<String,Set<String>> dependencyIndex=Map.of();
        RunState(String runId,String conversationId,String ownerUserId,String status,String executionMode,String routeReason,String planId,long seq,List<AgentRunEventView> events,Instant ignored){
            this.runId=runId;this.conversationId=conversationId;this.ownerUserId=ownerUserId;this.status=status;this.executionMode=executionMode;this.routeReason=routeReason;this.planId=planId;this.lastEventSequence=seq;this.events=events;
        }
    }
    
    /** 实现 TaskState 所代表的 Agent 运行时职责。 */
    private static final class TaskState {
        final String taskId,runId,planId;final int planVersion;final String taskKey,capabilityType,inputJson,inputHash;final List<String> dependencies;
        String status,outputUri,leaseOwner;Instant leaseUntil;int attempts;long leaseVersion;boolean authorized;
        TaskState(String taskId,String runId,String planId,int planVersion,String taskKey,String capabilityType,String status,String inputJson,String inputHash,int attempts,String outputUri,String leaseOwner,Instant leaseUntil,Long ignored,long leaseVersion,List<String> dependencies){
            this.taskId=taskId;this.runId=runId;this.planId=planId;this.planVersion=planVersion;this.taskKey=taskKey;this.capabilityType=capabilityType;this.status=status;this.inputJson=inputJson;this.inputHash=inputHash;this.attempts=attempts;this.outputUri=outputUri;this.leaseOwner=leaseOwner;this.leaseUntil=leaseUntil;this.leaseVersion=leaseVersion;this.dependencies=dependencies;
        }
    }
    
    /** 实现 ApprovalState 所代表的 Agent 运行时职责。 */
    private static final class ApprovalState {
        final String approvalId,runId,taskId,ownerUserId,actionType,parameterHash;String status;final Instant expiresAt;Instant usedAt;
        ApprovalState(String approvalId,String runId,String taskId,String ownerUserId,String actionType,String parameterHash,String status,Instant expiresAt,Instant usedAt){
            this.approvalId=approvalId;this.runId=runId;this.taskId=taskId;this.ownerUserId=ownerUserId;this.actionType=actionType;this.parameterHash=parameterHash;this.status=status;this.expiresAt=expiresAt;this.usedAt=usedAt;
        }
    }
}
