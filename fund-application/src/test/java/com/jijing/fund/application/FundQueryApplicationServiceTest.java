package com.jijing.fund.application;

import com.jijing.fund.application.exception.*;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class FundQueryApplicationServiceTest {
    private final FundRepository funds = mock(FundRepository.class);
    private final FundNavRepository navs = mock(FundNavRepository.class);
    private final FundQueryCache cache = mock(FundQueryCache.class);
    private final ExternalFundDataProvider provider = mock(ExternalFundDataProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), ZoneOffset.UTC);
    private final FundQueryApplicationService service = new FundQueryApplicationService(funds, navs, cache, provider, clock);

    @Test void returnsDatabaseProfileAndFillsCache() {
        FundCode code = new FundCode("000001");
        var profile = new FundProfile(code, "华夏成长混合", "混合型", "华夏基金", "王明", LocalDate.of(2001,12,18),
                "mock", Instant.parse("2026-01-31T00:00:00Z"), Instant.parse("2026-01-31T00:00:00Z"));
        when(cache.getProfile(code)).thenReturn(Optional.empty()); when(funds.findByCode(code)).thenReturn(Optional.of(profile));
        var result = service.getProfile("000001");
        assertEquals("华夏成长混合", result.name()); assertEquals("FRESH", result.freshness()); verify(cache).putProfile(profile);
    }

    @Test void rejectsReversedDateRangeWithoutRepositoryCall() {
        assertThrows(InvalidFundQueryException.class, () -> service.getNavHistory("000001", LocalDate.of(2026,2,1), LocalDate.of(2026,1,1)));
        verifyNoInteractions(navs);
    }

    @Test void loadsMissingFundFromProviderAndPersistsIt() {
        FundCode code = new FundCode("011612");
        var profile = new FundProfile(code, "华夏科创50ETF联接A", "指数型-股票", "华夏基金", "荣膺", null,
                "eastmoney-public", Instant.parse("2026-01-31T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        when(cache.getProfile(code)).thenReturn(Optional.empty());
        when(funds.findByCode(code)).thenReturn(Optional.empty());
        when(provider.fetchProfile(code)).thenReturn(Optional.of(profile));

        var result = service.getProfile("011612");

        assertEquals("华夏科创50ETF联接A", result.name());
        verify(funds).save(profile);
        verify(cache).putProfile(profile);
    }
}
