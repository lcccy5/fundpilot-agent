package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.calculator.FundMetricsCalculator;
import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.port.FundMetricsCache;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NavDataNotReadyException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.domain.repository.TradingCalendarRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 补齐指标查询的非法命令、基金缺失、净值未就绪和口径不被接受的失败路径。
 */
class FundMetricsQueryApplicationServiceReadabilityGapTest {
    private final FundRepository funds = mock(FundRepository.class);
    private final FundNavRepository navs = mock(FundNavRepository.class);
    private final TradingCalendarRepository calendar = mock(TradingCalendarRepository.class);
    private final FundMetricsCache cache = mock(FundMetricsCache.class);
    private final FundMetricsCalculator calculator = mock(FundMetricsCalculator.class);
    private final ExternalFundDataProvider provider = mock(ExternalFundDataProvider.class);
    private final Instant now = Instant.parse("2026-02-01T00:00:00Z");
    private FundMetricsQueryApplicationService service;

    /**
     * 失败路径不应走到计算器，因此计算上下文保持为空。
     */
    @BeforeEach
    void setUp() {
        service = new FundMetricsQueryApplicationService(funds, navs, calendar, cache, calculator, null, provider);
    }

    /**
     * 代码非法或区间缺失、颠倒时拒绝计算，并且不查询基金档案。
     */
    @Test
    void rejectsInvalidMetricsCommand() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);

        assertThrows(InvalidFundQueryException.class, () -> service.calculate("12", start, end, "UNIT_NAV"));
        assertThrows(InvalidFundQueryException.class, () -> service.calculate("000001", end, start, "UNIT_NAV"));
        assertThrows(InvalidFundQueryException.class, () -> service.calculate("000001", null, end, "UNIT_NAV"));

        verifyNoInteractions(funds, navs, provider, calculator);
    }

    /**
     * 本地和上游都没有档案时抛出基金不存在，并且不保存档案。
     */
    @Test
    void rejectsMetricsWhenFundIsMissing() {
        FundCode code = new FundCode("000001");
        when(funds.findByCode(code)).thenReturn(Optional.empty());
        when(provider.fetchProfile(code)).thenReturn(Optional.empty());

        FundNotFoundException error = assertThrows(FundNotFoundException.class,
                () -> service.calculate("000001", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "UNIT_NAV"));

        assertEquals("Fund not found: 000001", error.getMessage());
        verify(funds, never()).save(any());
        verifyNoInteractions(navs, calculator);
    }

    /**
     * 只有估算净值且上游也没有样本时抛出净值未就绪，并且不写入净值。
     */
    @Test
    void rejectsMetricsWhenConfirmedNavIsMissing() {
        FundCode code = new FundCode("000001");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        when(funds.findByCode(code)).thenReturn(Optional.of(profile(code)));
        when(navs.findHistory(code, start, end)).thenReturn(List.of(nav(code, NavStatus.ESTIMATED)));
        when(provider.fetchNavHistory(code, start, end)).thenReturn(List.of());

        NavDataNotReadyException error = assertThrows(NavDataNotReadyException.class,
                () -> service.calculate("000001", start, end, "UNIT_NAV"));

        assertEquals("NAV data is not ready for fund: 000001", error.getMessage());
        verify(navs, never()).upsertBatch(any());
        verifyNoInteractions(calculator);
    }

    /**
     * 无法识别的口径名称，以及累计净值不完整时，都拒绝计算。
     */
    @Test
    void rejectsUnsupportedNavBasis() {
        FundCode code = new FundCode("000001");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31);
        when(funds.findByCode(code)).thenReturn(Optional.of(profile(code)));
        when(navs.findHistory(code, start, end)).thenReturn(List.of(nav(code, NavStatus.CONFIRMED)));

        UnsupportedNavBasisException unknown = assertThrows(UnsupportedNavBasisException.class,
                () -> service.calculate("000001", start, end, "NOPE"));
        UnsupportedNavBasisException incomplete = assertThrows(UnsupportedNavBasisException.class,
                () -> service.calculate("000001", start, end, "accumulated_nav"));

        assertEquals("Unsupported nav basis: NOPE", unknown.getMessage());
        assertEquals("Accumulated NAV is incomplete", incomplete.getMessage());
        verifyNoInteractions(calculator);
    }

    private FundProfile profile(FundCode code) {
        return new FundProfile(code, "测试基金", "混合型", "公司", "经理", LocalDate.of(2020, 1, 1), "stub", now, now);
    }

    private NavPoint nav(FundCode code, NavStatus status) {
        return new NavPoint(code, LocalDate.of(2026, 1, 2), new BigDecimal("1.10"), null, null, status, "stub", now,
                now);
    }
}
