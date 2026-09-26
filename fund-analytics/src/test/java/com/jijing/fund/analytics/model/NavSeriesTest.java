package com.jijing.fund.analytics.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.model.FundCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定净值序列对 null、颠倒区间、越界日期和重复日期的拒绝方式。
 * 空观测和起止为同一天是允许的；收益计算会另因样本不足而不可用。
 */
class NavSeriesTest {

    /**
     * 锁定必填组件或观测元素为 null 时抛出空指针。
     * 这些输入若被收成空序列，断言失败。
     */
    @Test
    void rejectsNullComponentsAndNullObservation() {
        var start = LocalDate.of(2026, 1, 1);
        var end = LocalDate.of(2026, 1, 3);
        var observation = observation(start, "1.00");
        assertThatThrownBy(() -> series(null, NavBasis.UNIT_NAV, start, end, List.of(observation), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), null, start, end, List.of(observation), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, null, end, List.of(observation), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, null, List.of(observation), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, null, DataCoverage.of(1, 1), "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, List.of(observation), null, "1"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, List.of(observation), DataCoverage.of(1, 1), null))
                .isInstanceOf(NullPointerException.class);
        var observations = new ArrayList<NavObservation>();
        observations.add(null);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, observations, DataCoverage.of(0, 1), "1"))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * 锁定起始日晚于结束日、观测越界和重复日期都会被拒绝。
     * 起止为同一天、观测为空，以及相同合法输入重复构造，都必须成功且相等。
     */
    @Test
    void rejectsIllegalRangesAndAcceptsEmptyOrEqualBounds() {
        var start = LocalDate.of(2026, 1, 2);
        var end = LocalDate.of(2026, 1, 4);
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, end, start,
                List.of(observation(end, "1.00")), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("startDate must not be after endDate");
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end,
                List.of(observation(start.minusDays(1), "1.00")), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("observation outside requested range");
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end,
                List.of(observation(end.plusDays(1), "1.00")), DataCoverage.of(1, 1), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("observation outside requested range");
        assertThatThrownBy(() -> series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end,
                List.of(observation(start, "1.00"), observation(start, "1.10")), DataCoverage.of(2, 2), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate observation date");

        var sameDay = observation(start, "1.00");
        var equalBounds = series(new FundCode("000001"), NavBasis.UNIT_NAV, start, start, List.of(sameDay), DataCoverage.of(1, 1), "1");
        assertThat(equalBounds.observations()).containsExactly(sameDay);
        var empty = series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, List.of(), DataCoverage.of(0, 0), "1");
        var emptyAgain = series(new FundCode("000001"), NavBasis.UNIT_NAV, start, end, List.of(), DataCoverage.of(0, 0), "1");
        assertThat(empty.observations()).isEmpty();
        assertThat(emptyAgain).isEqualTo(empty);
    }

    /**
     * 组装一条正净值观测。
     * 净值不大于零时构造失败，序列测试不会收到这种观测。
     */
    private static NavObservation observation(LocalDate date, String nav) {
        return new NavObservation(date, new BigDecimal(nav));
    }

    /**
     * 按给定组件构造净值序列。
     * 非法区间和 null 组件由序列构造抛出，这里不再包一层。
     */
    private static NavSeries series(FundCode fundCode, NavBasis navBasis, LocalDate start, LocalDate end,
            List<NavObservation> observations, DataCoverage coverage, String dataVersion) {
        return new NavSeries(fundCode, navBasis, start, end, observations, coverage, dataVersion);
    }
}
