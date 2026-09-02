package com.jijing.fund.scheduler.knowledge;

import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.service.KnowledgeIngestionProcessor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.knowledge.worker",name="enabled",havingValue="true")
public final class KnowledgeIngestionWorker implements AutoCloseable {
    private final KnowledgeJobRepository jobs;private final KnowledgeIngestionProcessor processor;
    private final MeterRegistry meters;private final Clock clock;private final String workerId="knowledge-"+UUID.randomUUID();
    private final Duration lease;private final Semaphore permits;private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String,KnowledgeJobWorkItem> active=new ConcurrentHashMap<>();
    public KnowledgeIngestionWorker(KnowledgeJobRepository jobs,KnowledgeIngestionProcessor processor,MeterRegistry meters,Clock clock,
            @Value("${fund.knowledge.worker.lease-duration:60s}")Duration lease,
            @Value("${fund.knowledge.worker.concurrency:2}")int concurrency){this.jobs=jobs;this.processor=processor;this.meters=meters;this.clock=clock;this.lease=lease;this.permits=new Semaphore(Math.max(1,concurrency));}
    @Scheduled(fixedDelayString="${fund.knowledge.worker.poll-delay-ms:2000}") public void poll(){if(!permits.tryAcquire())return;try{jobs.claim(workerId,clock.instant(),lease).ifPresentOrElse(item->{active.put(item.jobId(),item);meters.counter("fund.knowledge.job.claim","result","claimed").increment();executor.submit(()->{try{processor.process(item);}finally{active.remove(item.jobId());permits.release();}});},()->{meters.counter("fund.knowledge.job.claim","result","empty").increment();permits.release();});}catch(RuntimeException error){meters.counter("fund.knowledge.job.claim","result","failed").increment();permits.release();}}
    @Scheduled(fixedDelayString="${fund.knowledge.worker.heartbeat-delay-ms:20000}") public void heartbeat(){Instant now=clock.instant();active.keySet().forEach(id->{if(!jobs.heartbeat(id,workerId,now,lease))meters.counter("fund.knowledge.job.lease.expired").increment();});}
    @Override public void close(){executor.shutdownNow();}
}
