package com.jijing.fund.domain.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** 验证 {@link NavPoint} 对必填字段和净值正数约束的校验。 */
class NavPointTest {
    private static final FundCode CODE = new FundCode("000001");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 1);
    private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

    /** 用给定的三种净值构造一条正式净值记录，其余字段取合法默认值。 */
    private static NavPoint nav(BigDecimal unit, BigDecimal accumulated, BigDecimal adjusted) {
        return new NavPoint(CODE, DATE, unit, accumulated, adjusted, NavStatus.CONFIRMED, "mock", null, NOW);
    }

    /** 单位净值为 0 或负数时抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonPositiveUnitNav() {
        assertThatThrownBy(() -> nav(BigDecimal.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unitNav");
        assertThatThrownBy(() -> nav(new BigDecimal("-1.0"), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unitNav");
    }

    /** 累计净值或复权净值一旦提供就必须为正，0 或负数被拒绝。 */
    @Test
    void rejectsNonPositiveOptionalNavs() {
        assertThatThrownBy(() -> nav(BigDecimal.ONE, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("accumulatedNav");
        assertThatThrownBy(() -> nav(BigDecimal.ONE, null, new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("adjustedNav");
    }

    /** 累计净值和复权净值可以缺失。 */
    @Test
    void allowsMissingOptionalNavs() {
        assertThatCode(() -> nav(BigDecimal.ONE, null, null)).doesNotThrowAnyException();
    }

    /** 基金代码、日期、单位净值、状态、来源、采集时间为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new NavPoint(null, DATE, BigDecimal.ONE, null, null, NavStatus.CONFIRMED, "mock", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NavPoint(CODE, null, BigDecimal.ONE, null, null, NavStatus.CONFIRMED, "mock", null, NOW))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> nav(null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NavPoint(CODE, DATE, BigDecimal.ONE, null, null, null, "mock", null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("navStatus");
        assertThatThrownBy(() -> new NavPoint(CODE, DATE, BigDecimal.ONE, null, null, NavStatus.CONFIRMED, null, null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("dataSource");
        assertThatThrownBy(() -> new NavPoint(CODE, DATE, BigDecimal.ONE, null, null, NavStatus.CONFIRMED, "mock", null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("collectedAt");
    }
}
