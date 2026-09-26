package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NoOverlappingPeriodException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import com.jijing.fund.domain.model.FundCode;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * 补齐基金对比的非法列表、无重叠区间和口径冲突失败路径。
 */
class FundComparisonApplicationServiceReadabilityGapTest {
    private final FundMetricsQueryUseCase metrics = mock(FundMetricsQueryUseCase.class);
    private final FundComparisonApplicationService service = new FundComparisonApplicationService(metrics);
    private final LocalDate start = LocalDate.of(2024, 1, 1);
    private final LocalDate end = LocalDate.of(2024, 12, 31);

    /**
     * 列表缺失、去重后不足两只或超过十只时拒绝对比，并且不计算指标。
     */
    @Test
    void rejectsInvalidComparisonCommand() {
        List<String> tooMany = IntStream.rangeClosed(1, 11).mapToObj(index -> String.format("%06d", index)).toList();

        InvalidFundQueryException missing = assertThrows(InvalidFundQueryException.class,
                () -> service.compare(null, start, end, "UNIT_NAV"));
        InvalidFundQueryException duplicates = assertThrows(InvalidFundQueryException.class,
                () -> service.compare(List.of("000001", "000001"), start, end, "UNIT_NAV"));
        InvalidFundQueryException oversized = assertThrows(InvalidFundQueryException.class,
                () -> service.compare(tooMany, start, end, "UNIT_NAV"));

        assertEquals("fundCodes are required", missing.getMessage());
        assertEquals("Comparison requires 2 to 10 unique funds", duplicates.getMessage());
        assertEquals("Comparison requires 2 to 10 unique funds", oversized.getMessage());
        verify(metrics, never()).calculate(any(), any(), any(), any());
    }

    /**
     * 实际起止日期全为空，或共同起点晚于共同终点时，抛出无重叠区间。
     */
    @Test
    void rejectsFundsWithoutAnOverlappingPeriod() {
        when(metrics.calculate(eq("000001"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000001", null, null, NavBasis.UNIT_NAV));
        when(metrics.calculate(eq("000002"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000002", null, null, NavBasis.UNIT_NAV));

        assertThrows(NoOverlappingPeriodException.class,
                () -> service.compare(List.of("000002", "000001"), start, end, "UNIT_NAV"));

        when(metrics.calculate(eq("000001"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000001", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 6, 1), NavBasis.UNIT_NAV));
        when(metrics.calculate(eq("000002"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000002", LocalDate.of(2021, 1, 1), LocalDate.of(2021, 6, 1), NavBasis.UNIT_NAV));

        NoOverlappingPeriodException disjoint = assertThrows(NoOverlappingPeriodException.class,
                () -> service.compare(List.of("000001", "000002"), start, end, "UNIT_NAV"));

        assertEquals("Funds have no overlapping NAV period", disjoint.getMessage());
        verify(metrics, never()).calculate(eq("000001"), eq(LocalDate.of(2021, 1, 1)), any(), any());
    }

    /**
     * 重叠区间存在但净值口径不一致时拒绝排名，并且不会按对齐区间重算。
     */
    @Test
    void rejectsMixedNavBasis() {
        when(metrics.calculate(eq("000001"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000001", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 6, 1), NavBasis.UNIT_NAV));
        when(metrics.calculate(eq("000002"), eq(start), eq(end), eq("UNIT_NAV")))
                .thenReturn(metrics("000002", LocalDate.of(2024, 2, 1), LocalDate.of(2024, 5, 1),
                        NavBasis.ACCUMULATED_NAV));

        UnsupportedNavBasisException error = assertThrows(UnsupportedNavBasisException.class,
                () -> service.compare(List.of("000002", "000001"), start, end, "UNIT_NAV"));

        assertEquals("Funds cannot be compared with mixed NAV basis", error.getMessage());
        verify(metrics, never()).calculate(any(), eq(LocalDate.of(2024, 2, 1)), eq(LocalDate.of(2024, 5, 1)), any());
    }

    private static FundMetrics metrics(String code, LocalDate actualStart, LocalDate actualEnd, NavBasis basis) {
        return new FundMetrics(new FundCode(code), LocalDate.of(2020, 1, 1), LocalDate.of(2024, 12, 31), actualStart,
                actualEnd, basis, 1, null, null, null, null, null, null, null, null, null, null, null, "v1", "1",
                null);
    }
}
