package com.jijing.fund.application;

import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.event.DomainEventPublisher;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class FundSyncApplicationServiceTest {
    @Test void synchronizesAndEvictsCache() {
        FundCode code = new FundCode("000001"); Instant now = Instant.parse("2026-02-01T00:00:00Z");
        var profile = new FundProfile(code,"测试基金","混合型","公司","经理",LocalDate.of(2020,1,1),"stub",now,now);
        var point = new NavPoint(code,LocalDate.of(2026,1,2),new BigDecimal("1.1"),new BigDecimal("1.1"),null,NavStatus.CONFIRMED,"stub",now,now);
        ExternalFundDataProvider provider = mock(ExternalFundDataProvider.class); FundRepository funds = mock(FundRepository.class);
        FundNavRepository navs = mock(FundNavRepository.class); FundSyncAuditRepository audits = mock(FundSyncAuditRepository.class);
        FundQueryCache cache = mock(FundQueryCache.class); FundSyncLock lock = mock(FundSyncLock.class);
        when(provider.sourceName()).thenReturn("stub"); when(provider.fetchProfile(code)).thenReturn(Optional.of(profile));
        when(provider.fetchNavHistory(code, LocalDate.of(2026,1,1), LocalDate.of(2026,1,31))).thenReturn(List.of(point));
        when(lock.tryLock(code)).thenReturn(true); when(audits.start(any(),any(),any(),any())).thenReturn(1L); when(navs.upsertBatch(any())).thenReturn(1);
        DomainEventPublisher events = mock(DomainEventPublisher.class);
        var service = new FundSyncApplicationService(provider,funds,navs,audits,cache,lock,Clock.fixed(now,ZoneOffset.UTC),events);
        var result = service.syncFund("000001",LocalDate.of(2026,1,1),LocalDate.of(2026,1,31));
        assertEquals(1,result.savedCount()); verify(funds).save(profile); verify(cache).evict(code); verify(lock).unlock(code);
        verify(events).append(eq("FUND_NAV_UPDATED"),eq("fund"),eq("000001"),isNull(),eq("v1"),eq("nav-000001-2026-01-02"),any());
    }
}
