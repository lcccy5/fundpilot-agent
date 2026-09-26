package com.jijing.fund.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.infrastructure.persistence.entity.FundNavEntity;
import com.jijing.fund.infrastructure.persistence.mapper.FundNavMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

/**
 * 净值仓储在缺行、空主键和同一代码日期重复写入上的现有行为。映射器是假的，不执行 SQL。
 */
@ExtendWith(MockitoExtension.class)
class MybatisFundNavRepositoryReadabilityGapTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private static final LocalDate END = LocalDate.of(2024, 1, 31);

    @Mock
    private FundNavMapper mapper;

    @InjectMocks
    private MybatisFundNavRepository repository;

    @Test
    void missingHistoryAndLatestBecomeEmptyResults() {
        when(mapper.findHistory("000001", START, END)).thenReturn(List.of());
        when(mapper.findLatest("000001")).thenReturn(null);

        assertThat(repository.findHistory(new FundCode("000001"), START, END)).isEmpty();
        assertThat(repository.findLatest(new FundCode("000001"))).isEmpty();
    }

    @Test
    void nullHistoryListAndNullStatusFailOnRead() {
        when(mapper.findHistory("000001", START, END)).thenReturn(null);
        assertThatThrownBy(() -> repository.findHistory(new FundCode("000001"), START, END))
                .isInstanceOf(NullPointerException.class);

        FundNavEntity row = storedNav();
        row.setNavStatus(null);
        when(mapper.findLatest("000001")).thenReturn(row);
        assertThatThrownBy(() -> repository.findLatest(new FundCode("000001")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullCodeFailsBeforeQueryAndNullSurrogateKeyStillMaps() {
        assertThatThrownBy(() -> repository.findLatest(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.upsertBatch(null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(mapper);

        FundNavEntity row = storedNav();
        row.setId(null);
        when(mapper.findLatest("000001")).thenReturn(row);

        assertThat(repository.findLatest(new FundCode("000001"))).hasValueSatisfying(point -> {
            assertThat(point.fundCode().value()).isEqualTo("000001");
            assertThat(point.navDate()).isEqualTo(LocalDate.of(2024, 1, 2));
        });
    }

    @Test
    void emptyBatchSkipsTheMapperAndDuplicateDatesStayInTheBatch() {
        assertThat(repository.upsertBatch(List.of())).isZero();
        verifyNoInteractions(mapper);

        NavPoint point = sampleNav();
        when(mapper.upsertBatch(any())).thenAnswer(invocation -> {
            List<FundNavEntity> items = invocation.getArgument(0);
            assertThat(items).hasSize(2);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.getId()).isNull();
                assertThat(item.getFundCode()).isEqualTo("000001");
                assertThat(item.getNavDate()).isEqualTo(LocalDate.of(2024, 1, 2));
            });
            return 2;
        });

        assertThat(repository.upsertBatch(List.of(point, point))).isEqualTo(2);
    }

    @Test
    void duplicateNavKeyIsNotCaught() {
        when(mapper.upsertBatch(any())).thenThrow(new DuplicateKeyException("uk_fund_nav_code_date"));

        assertThatThrownBy(() -> repository.upsertBatch(List.of(sampleNav())))
                .isInstanceOf(DuplicateKeyException.class);
        verify(mapper).upsertBatch(any());
    }

    private static NavPoint sampleNav() {
        return new NavPoint(
                new FundCode("000001"),
                LocalDate.of(2024, 1, 2),
                new BigDecimal("1.234000"),
                null,
                null,
                NavStatus.CONFIRMED,
                "eastmoney",
                null,
                Instant.parse("2024-01-02T15:00:00Z"));
    }

    private static FundNavEntity storedNav() {
        FundNavEntity row = new FundNavEntity();
        row.setId(4L);
        row.setFundCode("000001");
        row.setNavDate(LocalDate.of(2024, 1, 2));
        row.setUnitNav(new BigDecimal("1.234000"));
        row.setNavStatus(NavStatus.CONFIRMED.name());
        row.setDataSource("eastmoney");
        row.setCollectedAt(Instant.parse("2024-01-02T15:00:00Z"));
        return row;
    }
}
