package com.jijing.fund.agent.execution;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时拉取可执行计划任务，并回收到期租约。
 * 某一拍执行失败时异常会抛给调度器，本拍的恢复不会被吞掉后继续。
 */
@Component
public class AgentPlanWorkerLoop {
    private final AgentCoordinator coordinator;
    private final PlanTaskWorker worker;

    /**
     * 注入协调器和任务工作者。
     * 任一依赖为空时，构造成功，但第一次调度会失败。
     */
    public AgentPlanWorkerLoop(AgentCoordinator coordinator, PlanTaskWorker worker) {
        this.coordinator = coordinator;
        this.worker = worker;
    }

    /**
     * 用固定工作者标识处理最多 8 个任务，然后回收到期租约。
     * 领取或执行抛错时，本拍后续恢复不会执行。
     */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        Instant now = Instant.now();
        worker.drain("scheduled-worker", now, Duration.ofSeconds(30), 8);
        coordinator.recover(now);
    }
}
