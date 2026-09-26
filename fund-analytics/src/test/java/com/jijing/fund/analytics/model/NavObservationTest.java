package com.jijing.fund.analytics.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * 锁定单日净值拒绝 null 和非正数。
 * 相同的正净值重复构造必须相等。
 */
class NavObservationTest {

    /**
     * 锁定日期或净值为 null 时抛出带说明的空指针。
     * 若改成非法参数异常或默默替换，断言失败。
     */
    @Test
    void rejectsNullDateAndNav() {
        assertThatThrownBy(() -> new NavObservation(null, BigDecimal.ONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("date must not be null");
        assertThatThrownBy(() -> new NavObservation(LocalDate.of(2026, 1, 1), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("nav must not be null");
    }

    /**
     * 锁定零和负数净值被拒绝，正净值可以重复构造。
     * 非正净值若进入序列，后续收益率会出现除零或方向错误。
     */
    @Test
    void rejectsNonPositiveNavAndRepeatsPositiveObservation() {
        assertThatThrownBy(() -> new NavObservation(LocalDate.of(2026, 1, 1), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nav must be greater than zero");
        assertThatThrownBy(() -> new NavObservation(LocalDate.of(2026, 1, 1), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nav must be greater than zero");
        var first = new NavObservation(LocalDate.of(2026, 1, 1), new BigDecimal("1.2345"));
        var second = new NavObservation(LocalDate.of(2026, 1, 1), new BigDecimal("1.2345"));
        assertThat(first).isEqualTo(second);
    }
}
