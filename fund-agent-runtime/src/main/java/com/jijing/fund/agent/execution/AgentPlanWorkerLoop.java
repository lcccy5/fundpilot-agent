package com.jijing.fund.agent.execution;

import com.jijing.fund.agent.port.AgentDagRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
/** 实现 AgentPlanWorkerLoop 所代表的 Agent 运行时职责。 */
public class AgentPlanWorkerLoop {
    private final AgentCoordinator coordinator;
    private final PlanTaskWorker worker;
    
    /** 执行该 Agent 运行时组件中的 AgentPlanWorkerLoop 操作。 */
    public AgentPlanWorkerLoop(AgentCoordinator coordinator,PlanTaskWorker worker){
        this.coordinator=coordinator;this.worker=worker;
    }
    @Scheduled(fixedDelay=1000)
    
    /** 执行该 Agent 运行时组件中的 tick 操作。 */
    public void tick(){
        Instant now=Instant.now();
        worker.drain("scheduled-worker",now,Duration.ofSeconds(30),8);
        coordinator.recover(now);
    }
}
