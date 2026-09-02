package com.jijing.fund.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jijing.fund.agent.api.AgentRunCommand;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.agent.execution.AgentCoordinator;
import com.jijing.fund.agent.execution.ExecutionBudget;
import com.jijing.fund.agent.execution.Observation;
import com.jijing.fund.agent.execution.PlanTaskWorker;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HybridRuntimeTest {
    private final InMemoryAgentDagRepository dag=new InMemoryAgentDagRepository();
    private final AgentCoordinator coordinator=new AgentCoordinator(new ExecutionModeRouter(),dag);
    private final PlanTaskWorker worker=new PlanTaskWorker(dag);

    @Test void simpleQuestionDoesNotCreatePlan(){
        var run=coordinator.submit(new AgentRunCommand(null,"最大回撤是什么意思？","r1","user-a",true));
        assertThat(run.executionMode()).isEqualTo("DIRECT");
        assertThat(run.planId()).isNull();
        assertThat(run.status()).isEqualTo("SUCCEEDED");
    }

    @Test void planExecutesDagAndIsolatesOwners(){
        var run=coordinator.submit(new AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","r2","user-a",true));
        assertThat(run.executionMode()).isEqualTo("PLAN_AND_EXECUTE");
        worker.drain("w1",Instant.parse("2026-08-27T08:00:00Z"),Duration.ofSeconds(30),40);
        var done=coordinator.get(run.runId(),"user-a");
        assertThat(done.status()).isEqualTo("SUCCEEDED");
        var events=coordinator.events(run.runId(),"user-a",0L);
        assertThat(events).extracting(e->e.sequence()).doesNotHaveDuplicates();
        assertThat(events).extracting(e->e.eventType()).contains("run.routed","plan.validated","task.completed","run.completed");
        var replay=coordinator.events(run.runId(),"user-a",events.get(2).sequence());
        assertThat(replay.getFirst().sequence()).isEqualTo(events.get(3).sequence());
        assertThatThrownBy(()->coordinator.get(run.runId(),"user-b")).isInstanceOf(AgentRunNotFoundException.class);
    }

    @Test void exportWaitsForApprovalAndParameterHashMustMatch(){
        var run=coordinator.submit(new AgentRunCommand(null,"比较 000001 110022 161725 并导出研究报告","r3","user-a",true));
        worker.drain("w1",Instant.parse("2026-08-27T08:00:00Z"),Duration.ofSeconds(30),40);
        assertThat(coordinator.get(run.runId(),"user-a").status()).isEqualTo("WAITING_APPROVAL");
        String approvalId=coordinator.events(run.runId(),"user-a",0L).stream().filter(e->"approval.requested".equals(e.eventType())).findFirst().orElseThrow().payloadJson();
        String id=approvalId.replaceAll(".*\"approvalId\":\"([^\"]+)\".*","$1");
        assertThatThrownBy(()->coordinator.approve(run.runId(),id,"user-a","{\"format\":\"pdf\"}")).hasMessageContaining("approval");
        coordinator.approve(run.runId(),id,"user-a","{\"format\":\"markdown\"}");
        assertThat(coordinator.get(run.runId(),"user-a").status()).isEqualTo("SUCCEEDED");
    }

    @Test void twoWorkersNeverClaimTheSameTask()throws Exception{
        var run=coordinator.submit(new AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","r4","user-a",true));
        Set<String> claimed=ConcurrentHashMap.newKeySet();
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var f1=pool.submit(()->claimLoop(claimed,start,"w-a"));
            var f2=pool.submit(()->claimLoop(claimed,start,"w-b"));
            start.countDown();
            assertThat(f1.get(5,TimeUnit.SECONDS)+f2.get(5,TimeUnit.SECONDS)).isGreaterThan(0);
        }
        assertThat(coordinator.get(run.runId(),"user-a").status()).isIn("SUCCEEDED","PLAN_RUNNING","RUNNING");
        worker.drain("w-final",Instant.parse("2026-08-27T08:00:00Z"),Duration.ofSeconds(30),40);
        assertThat(coordinator.get(run.runId(),"user-a").status()).isEqualTo("SUCCEEDED");
    }

    @Test void expiredLeaseIsRecoveredWithoutRepeatingSuccess(){
        var run=coordinator.submit(new AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","r5","user-a",true));
        Instant t0=Instant.parse("2026-08-27T08:00:00Z");
        var first=dag.claimReady("w1",t0,Duration.ofSeconds(1)).orElseThrow();
        int recovered=dag.recoverExpiredLeases(t0.plusSeconds(5));
        assertThat(recovered).isEqualTo(1);
        worker.drain("w2",t0.plusSeconds(6),Duration.ofSeconds(30),40);
        assertThat(coordinator.get(run.runId(),"user-a").status()).isEqualTo("SUCCEEDED");
        assertThat(PlanTaskWorker.executionKey(first)).isNotBlank();
    }

    @Test void cancelStopsRemainingTasks(){
        var run=coordinator.submit(new AgentRunCommand(null,"比较 000001 110022 161725 并结合我的组合生成报告","r6","user-a",true));
        coordinator.cancel(run.runId(),"user-a");
        worker.drain("w1",Instant.parse("2026-08-27T08:00:00Z"),Duration.ofSeconds(30),10);
        assertThat(coordinator.get(run.runId(),"user-a").status()).isEqualTo("CANCELLED");
        assertThat(coordinator.plan(run.runId(),"user-a").tasks()).allMatch(t->"CANCELLED".equals(t.status())||"SUCCEEDED".equals(t.status()));
        assertThat(coordinator.get(run.runId(),"user-a").status()).isNotEqualTo("PAUSED");
    }

    @Test void reactStopsWhenEvidenceRepeats(){
        var budget=new ExecutionBudget(6,1000);
        var result=new com.jijing.fund.agent.execution.BoundedReactExecutor().run(round->new Observation("ok","s",List.of("ev-1"),List.of(),List.of(),"fresh",List.of()),budget);
        assertThat(result.stopReason()).isEqualTo("NO_NEW_EVIDENCE");
        assertThat(result.observations()).hasSize(3);
    }

    @Test void budgetCannotGoNegative(){
        var budget=new ExecutionBudget(1,1);
        assertThat(budget.consumeTools(1)).isTrue();
        assertThat(budget.consumeTools(1)).isFalse();
        assertThat(budget.remainingTools()).isZero();
    }

    @Test void routingEvalHasHighAccuracy(){
        var router=new ExecutionModeRouter();
        long plan=IntStream.range(0,200).mapToObj(i->switch(i%4){
            case 0->"最大回撤是什么意思？"+i;
            case 1->"查询 00000"+(i%9)+" 最新资料";
            case 2->"000001 最近为什么下跌？样本"+i;
            default->"比较 000001 110022 161725 并结合我的组合生成报告 "+i;
        }).filter(q->"PLAN_AND_EXECUTE".equals(router.route(q,true).mode().name())&&!q.contains("比较")).count();
        assertThat(plan).isZero();
        long reports=IntStream.range(0,50).mapToObj(i->"比较 000001 110022 161725 并结合我的组合生成报告 "+i)
                .filter(q->"PLAN_AND_EXECUTE".equals(router.route(q,true).mode().name())).count();
        assertThat(reports).isEqualTo(50);
    }

    private int claimLoop(Set<String> claimed,CountDownLatch start,String workerId){
        try{start.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}
        int n=0;for(int i=0;i<20;i++){
            var id=worker.claimAndExecute(workerId+"-"+UUID.randomUUID(),Instant.parse("2026-08-27T08:00:00Z"),Duration.ofSeconds(30));
            if(id.isEmpty())continue;
            if(!claimed.add(id.get()))throw new AssertionError("duplicate claim "+id.get());
            n++;
        }
        return n;
    }
}
