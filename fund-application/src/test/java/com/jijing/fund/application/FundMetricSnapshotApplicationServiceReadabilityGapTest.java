package com.jijing.fund.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.application.dto.MetricSnapshotBatchResult;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 确认指标快照重算把单只缺失和非法区间计入失败，并继续写入其余基金。
 */
class FundMetricSnapshotApplicationServiceReadabilityGapTest {
    private final FundRepository funds = mock(FundRepository.class);
    private final FundMetricsQueryUseCase metrics = mock(FundMetricsQueryUseCase.class);
    private final FundMetricSnapshotRepository snapshots = mock(FundMetricSnapshotRepository.class);
    private final FundMetricSnapshotApplicationService service =
            new FundMetricSnapshotApplicationService(funds, metrics, snapshots);

    /**
     * 一只基金的八个周期都因缺失失败、另一只因非法区间失败时，第三只仍写入八份快照。
     */
    @Test
    void countsMissingAndInvalidPeriodsWithoutStoppingTheBatch() {
        LocalDate end = LocalDate.of(2026, 2, 1);
        when(funds.findEnabledFundCodes(0, 100)).thenReturn(List.of(
                new FundCode("000001"), new FundCode("000002"), new FundCode("000003")));
        when(metrics.calculate(eq("000001"), any(), eq(end), isNull())).thenThrow(new FundNotFoundException("000001"));
        when(metrics.calculate(eq("000002"), any(), eq(end), isNull()))
                .thenThrow(new InvalidFundQueryException("Invalid metrics date range"));
        when(metrics.calculate(eq("000003"), any(), eq(end), isNull())).thenReturn(sample());

        MetricSnapshotBatchResult result = service.recomputeEnabledFunds(end);

        assertEquals(3, result.funds());
        assertEquals(8, result.snapshots());
        assertEquals(16, result.failures());
        verify(snapshots, times(8)).upsert(any());
    }

    private static FundMetrics sample() {
        return new FundMetrics(new FundCode("000003"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1), NavBasis.UNIT_NAV, 1, null, null, null, null, null,
                null, null, null, null, null, null, "v1", "1", null);
    }
}
