package com.jijing.fund.domain.portfolio;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;

public record UserPortfolio(PortfolioId portfolioId,UserId ownerUserId,String displayName,String currency,
                            PortfolioStatus status,long version,Instant createdAt,Instant updatedAt) {
    public UserPortfolio { if(displayName==null||displayName.isBlank())throw new IllegalArgumentException("portfolio name is required"); }
}
