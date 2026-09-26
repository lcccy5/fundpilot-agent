package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.SyncAlreadyRunningException;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.event.DomainEventPublisher;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.domain.repository.FundSyncAuditRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 补齐单只基金同步的非法区间、锁冲突和上游缺失失败路径。
 */
class FundSyncApplicationServiceReadabilityGapTest {
    private final ExternalFundDataProvider provider = mock(ExternalFundDataProvider.class);
    private final FundRepository funds = mock(FundRepository.class);
    private final FundNavRepository navs = mock(FundNavRepository.class);
    private final FundSyncAuditRepository audits = mock(FundSyncAuditRepository.class);
    private final FundQueryCache cache = mock(FundQueryCache.class);
    private final FundSyncLock lock = mock(FundSyncLock.class);
    private final Instant now = Instant.parse("2026-02-01T00:00:00Z");
    private FundSyncApplicationService service;

    /**
     * 每个用例使用同一组替身和固定时钟，避免失败路径误写真实仓储。
     */
    @BeforeEach
    void setUp() {
        service = new FundSyncApplicationService(provider, funds, navs, audits, cache, lock,
                Clock.fixed(now, ZoneOffset.UTC), mock(DomainEventPublisher.class));
    }

    /**
     * 区间颠倒、日期缺失或代码非法时拒绝同步，并且不加锁、不写审计。
     */
    @Test
    void rejectsInvalidSyncCommandBeforeLocking() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);

        assertThrows(InvalidFundQueryException.class, () -> service.syncFund("000001", end, start));
        assertThrows(InvalidFundQueryException.class, () -> service.syncFund("000001", null, end));
        InvalidFundQueryException malformed = assertThrows(InvalidFundQueryException.class,
                () -> service.syncFund("ABCDEF", start, end));

        assertEquals("fundCode must be exactly 6 digits", malformed.getMessage());
        verifyNoInteractions(lock, audits, provider, funds, navs, cache);
    }

    /**
     * 锁已被占用时抛出同步冲突，不开始审计，也不释放他人持有的锁。
     */
    @Test
    void rejectsSyncWhenLockIsAlreadyHeld() {
        FundCode code = new FundCode("000001");
        when(lock.tryLock(code)).thenReturn(false);

        SyncAlreadyRunningException error = assertThrows(SyncAlreadyRunningException.class,
                () -> service.syncFund("000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertEquals("Sync already running for fund: 000001", error.getMessage());
        verify(lock, never()).unlock(any());
        verifyNoInteractions(audits, provider, funds, navs, cache);
    }

    /**
     * 上游没有档案时抛出基金不存在，记下失败审计并释放本次锁，且不写入净值。
     */
    @Test
    void recordsAuditAndUnlocksWhenFundIsMissing() {
        FundCode code = new FundCode("000001");
        when(lock.tryLock(code)).thenReturn(true);
        when(provider.sourceName()).thenReturn("stub");
        when(audits.start(any(), any(), any(), any())).thenReturn(3L);
        when(provider.fetchProfile(code)).thenReturn(Optional.empty());

        FundNotFoundException error = assertThrows(FundNotFoundException.class,
                () -> service.syncFund("000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertEquals("Fund not found: 000001", error.getMessage());
        verify(audits).failure(eq(3L), eq("SYNC_FAILED"), eq("Fund not found: 000001"), eq(now));
        verify(lock).unlock(code);
        verify(navs, never()).upsertBatch(any());
        verify(funds, never()).save(any());
    }
}
