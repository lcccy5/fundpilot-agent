package com.jijing.fund.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.model.DataCoverage;
import com.jijing.fund.analytics.model.DrawdownPeriod;
import com.jijing.fund.analytics.model.FundMetricSnapshot;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.infrastructure.persistence.entity.FundMetricSnapshotEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundMetricSnapshotMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 指标快照在空身份、不可用指标和重复键上的现有行为。没有查询接口，缺行与覆盖走同一条 upsert。映射器是假的，不执行 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MybatisFundMetricSnapshotRepositoryReadabilityGapTest {
    @Mock
    private FundMetricSnapshotMapper mapper;

    @InjectMocks
    private MybatisFundMetricSnapshotRepository repository;

    @Test
    void unavailableMetricsAndUnknownCoverageAreWrittenAsNull() {
        repository.upsert(new FundMetricSnapshot("1Y", metrics(MetricValue.unavailable("gap"), "12", new BigDecimal("0.015"))));

        ArgumentCaptor<FundMetricSnapshotEntity> captor = ArgumentCaptor.forClass(FundMetricSnapshotEntity.class);
        verify(mapper).upsert(captor.capture());
        FundMetricSnapshotEntity stored = captor.getValue();
        assertThat(stored.cumulativeReturn).isNull();
        assertThat(stored.annualizedReturn).isNull();
        assertThat(stored.annualizedVolatility).isNull();
        assertThat(stored.maxDrawdown).isNull();
        assertThat(stored.sharpeRatio).isNull();
        assertThat(stored.positiveDayRatio).isNull();
        assertThat(stored.coverageRate).isNull();
        assertThat(stored.dataRevision).isEqualTo(12L);
        assertThat(stored.periodCode).isEqualTo("1Y");
    }

    @Test
    void nullDataVersionFailsBeforeUpsert() {
        assertThatThrownBy(() -> repository.upsert(new FundMetricSnapshot(
                "1Y",
                metrics(MetricValue.available(BigDecimal.ONE), null, new BigDecimal("0.015")))))
                .isInstanceOf(NumberFormatException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void nullSnapshotAndNullPeriodStillFollowTheCurrentBindingRules() {
        assertThatThrownBy(() -> repository.upsert(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);

        repository.upsert(new FundMetricSnapshot(null, metrics(MetricValue.available(BigDecimal.ONE), "3", null)));

        ArgumentCaptor<FundMetricSnapshotEntity> captor = ArgumentCaptor.forClass(FundMetricSnapshotEntity.class);
        verify(mapper).upsert(captor.capture());
        assertThat(captor.getValue().periodCode).isNull();
        assertThat(captor.getValue().riskFreeRate).isNull();
        assertThat(captor.getValue().dataRevision).isEqualTo(3L);
    }

    @Test
    void duplicateMetricIdentityIsNotCaught() {
        when(mapper.upsert(org.mockito.ArgumentMatchers.any())).thenThrow(new DuplicateKeyException("uk_metric_identity"));

        assertThatThrownBy(() -> repository.upsert(new FundMetricSnapshot(
                "1Y",
                metrics(MetricValue.available(BigDecimal.ONE), "3", new BigDecimal("0.015")))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static FundMetrics metrics(MetricValue metric, String dataVersion, BigDecimal riskFreeRate) {
        return new FundMetrics(
                new FundCode("000001"),
                LocalDate.of(2024, 1, 1),
                LocalDate.of(2024, 12, 31),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 12, 30),
                NavBasis.ADJUSTED_NAV,
                0,
                DataCoverage.of(0, 0),
                metric,
                metric,
                metric,
                metric,
                new DrawdownPeriod(null, null, null),
                metric,
                metric,
                metric,
                metric,
                riskFreeRate,
                "v1",
                dataVersion,
                Instant.parse("2024-12-31T00:00:00Z"));
    }
}
