package com.jijing.fund.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jijing.fund.infrastructure.persistence.mapper.TradingCalendarMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 交易日历只有计数。没有命中和空参数都表现为 0，本仓储不插入行，因此没有重复键路径。
 */
@ExtendWith(MockitoExtension.class)
class MybatisTradingCalendarRepositoryReadabilityGapTest {
    @Mock
    private TradingCalendarMapper mapper;

    @InjectMocks
    private MybatisTradingCalendarRepository repository;

    @Test
    void missingTradingDaysAndNullBoundsBothReturnZero() {
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end = LocalDate.of(2024, 1, 31);
        when(mapper.countTradingDays("CN", start, end)).thenReturn(0);
        when(mapper.countTradingDays(null, null, null)).thenReturn(0);

        assertThat(repository.countTradingDays("CN", start, end)).isZero();
        assertThat(repository.countTradingDays(null, null, null)).isZero();

        verify(mapper).countTradingDays("CN", start, end);
        verify(mapper).countTradingDays(null, null, null);
    }
}
