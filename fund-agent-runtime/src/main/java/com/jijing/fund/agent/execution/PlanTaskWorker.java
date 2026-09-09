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

    
    /**
     * Executes on an interruptible virtual thread while this thread renews the lease.
     * Losing or being unable to confirm ownership cancels execution without publishing a failure.
     */
    public Optional<String> claimAndExecute(String workerId,Instant now,Duration lease){
        if(lease.toMillis()<3)throw new IllegalArgumentException("lease must be at least 3ms");
        long started=System.nanoTime();
        java.util.function.Supplier<Instant> current=()->now.plusNanos(System.nanoTime()-started);
        Optional<ClaimedTask> claimed=dag.claimReady(workerId,current.get(),lease);
        if(claimed.isEmpty())return Optional.empty();
        ClaimedTask task=claimed.get();
        var active=new java.util.concurrent.atomic.AtomicBoolean(true);
        Runnable check=()->{
            if(!active.get()||Thread.currentThread().isInterrupted())throw new com.jijing.fund.agent.exception.TaskLeaseLostException(task.taskId());
            // Also consult durable ownership before each new capability/graph operation.
            dag.withLease(task,current.get(),()->{});
        };
        var context=new CapabilityExecutionContext(task,check,writes->{check.run();dag.withLease(task,current.get(),writes);});
        var future=new java.util.concurrent.FutureTask<Void>(()->{
            check.run();
            if(AgentCapabilityRegistry.APPROVAL_REQUIRED.contains(task.capabilityType())&&!dag.isSideEffectAuthorized(task.taskId())){
                context.persist(()->{
                    String approvalId=dag.requestApproval(task.runId(),task.taskId(),task.ownerUserId(),task.capabilityType(),sha(task.inputJson()),"export requires approval",Instant.now().plus(Duration.ofHours(1)),current.get());
                    dag.markWaitingApproval(task,approvalId,current.get());
                });
                return null;
            }
            String key=executionKey(task);
            try{
                if(dag.alreadySucceeded(key)){
                    dag.completeTask(task,key,"artifact://reused/"+key,List.of("ev-reuse"),current.get());
                }else{
                    check.run();
                    var result=executors.require(task.capabilityType()).execute(context);
                    check.run();
                    dag.completeTask(task,key,result.outputUri(),result.evidenceIds(),current.get());
                }
            }catch(com.jijing.fund.agent.exception.TaskLeaseLostException lost){throw lost;}
            catch(RuntimeException error){
                check.run();
                String reason=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
                dag.failTask(task,reason,current.get());
            }
            return null;
        });
        Thread.ofVirtual().name("plan-task-"+task.taskId()).start(future);
        try{
            while(true){
                try{future.get(Math.max(1,lease.toMillis()/3),java.util.concurrent.TimeUnit.MILLISECONDS);break;}
                catch(java.util.concurrent.TimeoutException timeout){
                    if(!dag.renewLease(task,current.get(),lease))break;
                }
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        catch(java.util.concurrent.ExecutionException failure){
            if(!(failure.getCause() instanceof com.jijing.fund.agent.exception.TaskLeaseLostException))
                throw new IllegalStateException("task execution could not be persisted",failure.getCause());
        }finally{
            active.set(false);
            future.cancel(true);
        }
        return Optional.of(task.taskId());
    }

    /** 执行该 Agent 运行时组件中的 drain 操作。 */
    public int drain(String workerId,Instant now,Duration lease,int max){
        long started=System.nanoTime();
        int n=0;while(n<max&&!Thread.currentThread().isInterrupted()&&claimAndExecute(workerId,now.plusNanos(System.nanoTime()-started),lease).isPresent())n++;return n;
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
