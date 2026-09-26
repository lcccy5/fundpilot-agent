package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 补齐基金查询里尚未覆盖的缺失与非法命令失败路径。
 */
class FundQueryApplicationServiceReadabilityGapTest {
    private final FundRepository funds = mock(FundRepository.class);
    private final FundNavRepository navs = mock(FundNavRepository.class);
    private final FundQueryCache cache = mock(FundQueryCache.class);
    private final ExternalFundDataProvider provider = mock(ExternalFundDataProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    private final FundQueryApplicationService service =
            new FundQueryApplicationService(funds, navs, cache, provider, clock);

    /**
     * 代码不是六位数字时拒绝查询，并且不访问缓存、仓储或外部数据源。
     */
    @Test
    void rejectsMalformedFundCodeBeforeAnyLookup() {
        InvalidFundQueryException error = assertThrows(InvalidFundQueryException.class,
                () -> service.getProfile("12"));

        assertEquals("fundCode must be exactly 6 digits", error.getMessage());
        verifyNoInteractions(cache, funds, navs, provider);
    }

    /**
     * 净值历史缺少起点或终点时拒绝查询，并且不读取净值仓储。
     */
    @Test
    void rejectsMissingNavHistoryDates() {
        LocalDate day = LocalDate.of(2026, 1, 1);

        assertThrows(InvalidFundQueryException.class, () -> service.getNavHistory("000001", null, day));
        assertThrows(InvalidFundQueryException.class, () -> service.getNavHistory("000001", day, null));

        verifyNoInteractions(navs);
    }

    /**
     * 本地和外部数据源都没有档案时抛出基金不存在，并且不写入仓储或缓存。
     */
    @Test
    void rejectsProfileWhenFundIsMissingEverywhere() {
        FundCode code = new FundCode("000001");
        when(cache.getProfile(code)).thenReturn(Optional.empty());
        when(funds.findByCode(code)).thenReturn(Optional.empty());
        when(provider.fetchProfile(code)).thenReturn(Optional.empty());

        FundNotFoundException error = assertThrows(FundNotFoundException.class, () -> service.getProfile("000001"));

        assertEquals("Fund not found: 000001", error.getMessage());
        verify(funds, never()).save(org.mockito.ArgumentMatchers.any());
        verify(cache, never()).putProfile(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 历史区间已经有净值样本、但仓储没有档案时，不再回源，并按调用方原文抛出基金不存在。
     */
    @Test
    void rejectsHistoryWhenFundRecordIsMissing() {
        FundCode code = new FundCode("000001");
        LocalDate start = LocalDate.of(2020, 1, 1);
        LocalDate end = LocalDate.of(2020, 1, 31);
        NavPoint point = new NavPoint(code, LocalDate.of(2020, 1, 2), new BigDecimal("1.10"), new BigDecimal("1.10"),
                null, NavStatus.CONFIRMED, "stub", clock.instant(), clock.instant());
        when(cache.getHistory(code, start, end)).thenReturn(Optional.empty());
        when(navs.findHistory(code, start, end)).thenReturn(List.of(point));
        when(funds.findByCode(code)).thenReturn(Optional.empty());

        FundNotFoundException error = assertThrows(FundNotFoundException.class,
                () -> service.getNavHistory("000001", start, end));

        assertEquals("Fund not found: 000001", error.getMessage());
        verify(provider, never()).fetchProfile(org.mockito.ArgumentMatchers.any());
        verify(provider, never()).fetchNavHistory(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
