package com.jijing.fund.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证 {@link PortfolioId} 对缺失值和非 UUID 值的拒绝，以及随机生成的可用性。 */
class PortfolioIdTest {
    /** null、空串和纯空白都抛出 IllegalArgumentException，并提示标识必填。 */
    @Test
    void rejectsMissingValue() {
        assertThatThrownBy(() -> new PortfolioId(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolioId is required");
        assertThatThrownBy(() -> new PortfolioId("")).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolioId is required");
        assertThatThrownBy(() -> new PortfolioId("  ")).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolioId is required");
    }

    /** 无法解析为 UUID 的字符串抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonUuidValue() {
        assertThatThrownBy(() -> new PortfolioId("portfolio-1")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 多次调用 random() 得到的标识互不相同，且都能被重新构造。 */
    @Test
    void randomProducesDistinctValidIds() {
        PortfolioId first = PortfolioId.random();
        PortfolioId second = PortfolioId.random();

        assertThat(first).isNotEqualTo(second);
        assertThat(new PortfolioId(first.value())).isEqualTo(first);
    }
}
