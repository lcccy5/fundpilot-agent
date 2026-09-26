package com.jijing.fund.scheduler.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.knowledge.domain.FundDocumentType;
import com.jijing.fund.knowledge.domain.KnowledgeJobWorkItem;
import com.jijing.fund.knowledge.port.KnowledgeJobRepository;
import com.jijing.fund.knowledge.service.KnowledgeIngestionProcessor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 锁定知识入库轮询在空领取、领取失败、许可耗尽、处理失败和关闭后的行为。
 * 工人没有净值序列参数；失败体现在计数、许可和在途任务，而不是返回空收益率。
 */
class KnowledgeIngestionWorkerTest {
    private KnowledgeJobRepository jobs;
    private KnowledgeIngestionProcessor processor;
    private SimpleMeterRegistry meters;
    private Clock clock;
    private KnowledgeIngestionWorker worker;

    /**
     * 准备一个并发为 1 的工人。
     * 依赖替身默认不领取任务；具体失败由各测试自行设定。
     */
    @BeforeEach
    void setUp() {
        jobs = Mockito.mock(KnowledgeJobRepository.class);
        processor = Mockito.mock(KnowledgeIngestionProcessor.class);
        meters = new SimpleMeterRegistry();
        clock = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"), ZoneOffset.UTC);
        worker = new KnowledgeIngestionWorker(jobs, processor, meters, clock, Duration.ofSeconds(60), 1);
    }

    /**
     * 关闭测试中的执行器，避免虚拟线程留到下个用例。
     * 重复关闭是安全的；已经关闭过的工人再次关闭不会抛出异常。
     */
    @AfterEach
    void tearDown() {
        worker.close();
    }

    /**
     * 锁定连续空领取只增加空轮询计数，并且不会进入处理。
     * 若空结果占用许可导致第二次无法领取，断言失败。
     */
    @Test
    void countsRepeatedEmptyClaims() {
        when(jobs.claim(anyString(), any(), any())).thenReturn(Optional.empty());

        worker.poll();
        worker.poll();

        assertThat(meters.counter("fund.knowledge.job.claim", "result", "empty").count()).isEqualTo(2);
        verify(processor, never()).process(any());
    }

    /**
     * 锁定仓库抛出运行时异常时记失败并归还许可，因此可以立刻再次轮询。
     * 异常若冒出测试线程，或第二次不再访问仓库，断言失败。
     */
    @Test
    void countsRepeatedClaimFailuresWithoutLeakingThePermit() {
        when(jobs.claim(anyString(), any(), any())).thenThrow(new IllegalStateException("repository down"));

        worker.poll();
        worker.poll();

        assertThat(meters.counter("fund.knowledge.job.claim", "result", "failed").count()).isEqualTo(2);
        verify(jobs, times(2)).claim(anyString(), any(), any());
        verify(processor, never()).process(any());
    }

    /**
     * 锁定仓库为 null 时轮询不向外抛出，而是连续记入失败计数。
     * 空指针若逃出轮询，调用方会把它当成未处理的崩溃，而不是一次失败领取。
     */
    @Test
    void countsNullRepositoryAsClaimFailure() {
        SimpleMeterRegistry localMeters = new SimpleMeterRegistry();
        KnowledgeIngestionWorker broken = new KnowledgeIngestionWorker(
                null, processor, localMeters, clock, Duration.ofSeconds(60), 1);
        try {
            broken.poll();
            broken.poll();
            assertThat(localMeters.counter("fund.knowledge.job.claim", "result", "failed").count()).isEqualTo(2);
        } finally {
            broken.close();
        }
    }

    /**
     * 锁定唯一许可被在途任务占用时，下一次轮询直接返回且不再领取。
     * 等待超时说明任务没有启动；若第二次仍访问仓库，说明许可没有挡住并发。
     */
    @Test
    void skipsPollWhenTheOnlyPermitIsHeld() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            when(jobs.claim(anyString(), any(), any())).thenReturn(Optional.of(workItem("job-held")));
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                return null;
            }).when(processor).process(any());

            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.poll();

            verify(jobs, times(1)).claim(anyString(), any(), any());
        } finally {
            finish.countDown();
        }
    }

    /**
     * 锁定处理阶段抛错后许可仍会归还，后续轮询可以再领取到空结果。
     * 若许可泄漏，等待会超时。任务线程上的异常不会改写成领取失败计数。
     */
    @Test
    void releasesPermitAfterProcessorFails() {
        AtomicInteger claims = new AtomicInteger();
        when(jobs.claim(anyString(), any(), any())).thenAnswer(invocation -> {
            if (claims.getAndIncrement() == 0) {
                return Optional.of(workItem("job-fail"));
            }
            return Optional.empty();
        });
        doThrow(new IllegalStateException("process failed")).when(processor).process(any());

        worker.poll();

        Awaitility.await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            worker.poll();
            assertThat(meters.counter("fund.knowledge.job.claim", "result", "empty").count()).isEqualTo(1);
        });
    }

    /**
     * 锁定没有在途任务时续租不做计数；任务占住许可期间，失败续租可以重复计数。
     * 续租返回 false 时任务仍留在在途表中，所以第二次续租还会再计一次。
     */
    @Test
    void countsRepeatedExpiredHeartbeatsOnlyWhileJobIsActive() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            when(jobs.claim(anyString(), any(), any())).thenReturn(Optional.of(workItem("job-lease")));
            when(jobs.heartbeat(anyString(), anyString(), any(), any())).thenReturn(false);
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                return null;
            }).when(processor).process(any());

            worker.heartbeat();
            assertThat(meters.counter("fund.knowledge.job.lease.expired").count()).isZero();
            worker.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            worker.heartbeat();
            worker.heartbeat();
            assertThat(meters.counter("fund.knowledge.job.lease.expired").count()).isEqualTo(2);
        } finally {
            finish.countDown();
        }
    }

    /**
     * 锁定关闭执行器后再领取时，提交失败会计入失败次数，但任务仍留在在途表里。
     * 因此随后的续租仍能看到它，并在续租失败时重复计数。重复关闭本身不抛出异常。
     */
    @Test
    void keepsClaimedJobActiveWhenSubmitFailsAfterClose() {
        when(jobs.claim(anyString(), any(), any())).thenReturn(Optional.of(workItem("job-closed")));
        when(jobs.heartbeat(anyString(), anyString(), any(), any())).thenReturn(false);

        worker.close();
        worker.close();
        worker.poll();
        worker.heartbeat();
        worker.heartbeat();

        assertThat(meters.counter("fund.knowledge.job.claim", "result", "failed").count()).isEqualTo(1);
        assertThat(meters.counter("fund.knowledge.job.lease.expired").count()).isEqualTo(2);
        verify(processor, never()).process(any());
    }

    /**
     * 锁定并发上限小于 1 时仍只有一个许可，第二轮在任务未结束前不会再领取。
     * 若零并发被理解成不设上限，第二次领取会出现，断言失败。
     */
    @Test
    void treatsNonPositiveConcurrencyAsASinglePermit() throws Exception {
        KnowledgeIngestionWorker limited = new KnowledgeIngestionWorker(
                jobs, processor, meters, clock, Duration.ofSeconds(60), 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            when(jobs.claim(anyString(), any(), any())).thenReturn(Optional.of(workItem("job-one")));
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                return null;
            }).when(processor).process(any());

            limited.poll();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            limited.poll();

            verify(jobs, times(1)).claim(anyString(), any(), any());
        } finally {
            finish.countDown();
            limited.close();
        }
    }

    /**
     * 组装一条可放入在途表的任务。
     * 基金代码集合为 null 时会变成空集合；任务号为 null 时，放入在途表会抛出空指针并被轮询记成领取失败。
     */
    private static KnowledgeJobWorkItem workItem(String jobId) {
        return new KnowledgeJobWorkItem(jobId, "doc-1", "ver-1", "storage-key", "file.pdf", "application/pdf",
                "title", FundDocumentType.OTHER, LocalDate.of(2026, 1, 1), Set.of(), "source", "uri", 1, "REGISTERED", 0);
    }
}
