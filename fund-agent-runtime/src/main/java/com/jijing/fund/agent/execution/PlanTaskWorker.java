package com.jijing.fund.agent.execution;

import com.jijing.fund.agent.capability.CapabilityExecutionContext;
import com.jijing.fund.agent.capability.CapabilityExecutorRegistry;
import com.jijing.fund.agent.planning.AgentCapabilityRegistry;
import com.jijing.fund.agent.port.AgentDagRepository;
import com.jijing.fund.agent.port.AgentDagRepository.ClaimedTask;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** 实现 PlanTaskWorker 所代表的 Agent 运行时职责。 */
public final class PlanTaskWorker {
    public static final String SCHEMA="capability-v1";
    private final AgentDagRepository dag;
    private final CapabilityExecutorRegistry executors;
    
    /** 获取当前 Agent 操作所需的 PlanTaskWorker 结果。 */
    public PlanTaskWorker(AgentDagRepository dag){this(dag,CapabilityExecutorRegistry.legacyForUnitTests());}
    
    /** 获取当前 Agent 操作所需的 PlanTaskWorker 结果。 */
    public PlanTaskWorker(AgentDagRepository dag,CapabilityExecutorRegistry executors){this.dag=dag;this.executors=executors;}

    
    /** 通过 claimAndExecute 操作更新持久化或内存中的运行状态。 */
    public Optional<String> claimAndExecute(String workerId,Instant now,Duration lease){
        Optional<ClaimedTask> claimed=dag.claimReady(workerId,now,lease);
        if(claimed.isEmpty())return Optional.empty();
        ClaimedTask task=claimed.get();
        if(AgentCapabilityRegistry.APPROVAL_REQUIRED.contains(task.capabilityType())&&!dag.isSideEffectAuthorized(task.taskId())){
            String hash=sha(task.inputJson());
            String approvalId=dag.requestApproval(task.runId(),task.taskId(),task.ownerUserId(),task.capabilityType(),hash,"export requires approval",Instant.now().plus(Duration.ofHours(1)),now);
            dag.markWaitingApproval(task.taskId(),approvalId,now);
            return Optional.of(task.taskId());
        }
        String key=executionKey(task);
        if(dag.alreadySucceeded(key)){
            dag.completeTask(task.taskId(),key,"artifact://reused/"+key,List.of("ev-reuse"),now);
            return Optional.of(task.taskId());
        }
        var result=executors.require(task.capabilityType()).execute(new CapabilityExecutionContext(task));
        dag.completeTask(task.taskId(),key,result.outputUri(),result.evidenceIds(),now);
        return Optional.of(task.taskId());
    }

    
    /** 执行该 Agent 运行时组件中的 drain 操作。 */
    public int drain(String workerId,Instant now,Duration lease,int max){
        int n=0;while(n<max&&claimAndExecute(workerId,now,lease).isPresent())n++;return n;
    }

    
    /** 执行该 Agent 运行时组件中的 executionKey 操作。 */
    public static String executionKey(ClaimedTask task){
        return sha(task.planId()+"|"+task.planVersion()+"|"+task.taskKey()+"|"+task.inputHash()+"|"+SCHEMA);
    }
    
    /** 构造后续 Agent 处理所需的 sha 值。 */
    private static String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}
