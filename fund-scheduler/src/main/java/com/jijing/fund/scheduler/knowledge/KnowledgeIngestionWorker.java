package com.jijing.fund.scheduler.knowledge;

import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.service.KnowledgeIngestionProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 轮询并租约执行知识入库任务，同时为在途任务续租。
 * 只有配置开启时才装配。领取失败会归还许可并计数；处理阶段的异常留在任务线程里，由处理器自行落失败状态。重复轮询在许可允许时会继续领取。
 */
@Component
@ConditionalOnProperty(prefix = "fund.knowledge.worker", name = "enabled", havingValue = "true")
public final class KnowledgeIngestionWorker implements AutoCloseable {
    private final KnowledgeJobRepository jobs;
    private final KnowledgeIngestionProcessor processor;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String workerId = "knowledge-" + UUID.randomUUID();
    private final Duration lease;
    private final Semaphore permits;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, KnowledgeJobWorkItem> active = new ConcurrentHashMap<>();

    /**
     * 绑定仓库、处理器、指标、时钟、租约时长和并发上限。
     * 并发小于 1 时按 1 个许可处理，避免零许可把轮询永久跳过。不在构造时领取任务。依赖为 null 时要到轮询或续租才失败。
     */
    public KnowledgeIngestionWorker(KnowledgeJobRepository jobs, KnowledgeIngestionProcessor processor, MeterRegistry meters, Clock clock,
            @Value("${fund.knowledge.worker.lease-duration:60s}") Duration lease,
            @Value("${fund.knowledge.worker.concurrency:2}") int concurrency) {
        this.jobs = jobs;
        this.processor = processor;
        this.meters = meters;
        this.clock = clock;
        this.lease = lease;
        this.permits = new Semaphore(Math.max(1, concurrency));
    }

    /**
     * 在仍有空闲许可时领取一条任务并异步执行。
     * 许可已满时立即返回，不访问仓库。仓库抛出运行时异常、领取结果无法读取，或执行器拒绝提交时，失败计数加一并归还许可。
     * 领取为空时记一次空轮询并归还许可。任务一旦提交，无论处理成功还是抛错，都要等任务结束才移出在途表并归还许可。
     * 执行器已关闭时，任务会先进入在途表，提交失败后仍留在表中。重复调用在许可空闲时会再次领取。
     */
    @Scheduled(fixedDelayString = "${fund.knowledge.worker.poll-delay-ms:2000}")
    public void poll() {
        if (!permits.tryAcquire()) {
            return;
        }
        try {
            jobs.claim(workerId, clock.instant(), lease).ifPresentOrElse(item -> {
                active.put(item.jobId(), item);
                meters.counter("fund.knowledge.job.claim", "result", "claimed").increment();
                executor.submit(() -> {
                    try {
                        processor.process(item);
                    } finally {
                        active.remove(item.jobId());
                        permits.release();
                    }
                });
            }, () -> {
                meters.counter("fund.knowledge.job.claim", "result", "empty").increment();
                permits.release();
            });
        } catch (RuntimeException error) {
            meters.counter("fund.knowledge.job.claim", "result", "failed").increment();
            permits.release();
        }
    }

    /**
     * 为每条在途任务续租。
     * 在途表为空时不访问仓库。续租返回 false 时只累加租约过期计数，不把任务移出在途表。同一任务重复续租会重复询问仓库。
     */
    @Scheduled(fixedDelayString = "${fund.knowledge.worker.heartbeat-delay-ms:20000}")
    public void heartbeat() {
        Instant now = clock.instant();
        active.keySet().forEach(id -> {
            if (!jobs.heartbeat(id, workerId, now, lease)) {
                meters.counter("fund.knowledge.job.lease.expired").increment();
            }
        });
    }

    /**
     * 立即关闭虚拟线程执行器，并尝试中断在途任务。
     * 重复关闭是安全的。已经放进在途表但尚未提交成功的任务不会在这里被移除。
     */
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
