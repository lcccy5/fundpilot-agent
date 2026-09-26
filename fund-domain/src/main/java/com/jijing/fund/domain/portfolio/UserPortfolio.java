package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;

/** 用户的一个投资组合，包含展示名、币种、状态和用于乐观锁的版本号。 */
public record UserPortfolio(PortfolioId portfolioId, UserId ownerUserId, String displayName, String currency,
                            PortfolioStatus status, long version, Instant createdAt, Instant updatedAt) {
    /**
     * 校验组合展示名：为 null 或空白时抛出 IllegalArgumentException（"portfolio name is required"）。
     * 组合标识、所属用户、币种和状态不校验，可为 null。
     */
    public UserPortfolio {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("portfolio name is required");
        }
    }
}
