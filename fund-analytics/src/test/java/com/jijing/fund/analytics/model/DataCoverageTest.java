package com.jijing.fund.analytics.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 锁定覆盖率阈值，以及预期交易日无效时的未知状态。
 * 相同输入重复计算必须相等。实际点数为负时不会被拒绝。
 */
class DataCoverageTest {

    /**
     * 锁定预期交易日小于等于零时状态未知、覆盖率为 null，并且重复调用相等。
     * 若抛出除零或把未知收成完整，断言失败。
     */
    @Test
    void returnsUnknownWhenExpectedTradingDaysAreNotPositive() {
        var zero = DataCoverage.of(3, 0);
        var negative = DataCoverage.of(-1, -5);
        assertThat(zero.status()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(zero.rate()).isNull();
        assertThat(zero.actualObservations()).isEqualTo(3);
        assertThat(negative.status()).isEqualTo(CoverageStatus.UNKNOWN);
        assertThat(negative.rate()).isNull();
        assertThat(DataCoverage.of(3, 0)).isEqualTo(zero);
    }

    /**
     * 锁定 0.98 与 0.90 两条阈值的归类。
     * 等于阈值时归入更高一档；再低一档才变为部分或不足。
     */
    @Test
    void classifiesCoverageThresholds() {
        assertThat(DataCoverage.of(98, 100).status()).isEqualTo(CoverageStatus.COMPLETE);
        assertThat(DataCoverage.of(97, 100).status()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(DataCoverage.of(90, 100).status()).isEqualTo(CoverageStatus.PARTIAL);
        assertThat(DataCoverage.of(89, 100).status()).isEqualTo(CoverageStatus.INSUFFICIENT);
        assertThat(DataCoverage.of(98, 100)).isEqualTo(DataCoverage.of(98, 100));
    }
}
