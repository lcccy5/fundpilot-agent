package com.jijing.fund.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link UserPortfolio} 要求组合展示名非空。 */
class UserPortfolioTest {
    /** 用给定展示名构造一个正常状态的组合。 */
    private static UserPortfolio portfolio(String name) {
        return new UserPortfolio(PortfolioId.random(), UserId.random(), name, "CNY", PortfolioStatus.ACTIVE, 0,
                Instant.EPOCH, Instant.EPOCH);
    }

    /** 展示名为 null、空串或纯空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingDisplayName() {
        assertThatThrownBy(() -> portfolio(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolio name is required");
        assertThatThrownBy(() -> portfolio("")).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolio name is required");
        assertThatThrownBy(() -> portfolio("   ")).isInstanceOf(IllegalArgumentException.class).hasMessage("portfolio name is required");
    }
}
