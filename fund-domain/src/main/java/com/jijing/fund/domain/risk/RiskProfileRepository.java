package com.jijing.fund.domain.risk;

import com.jijing.fund.domain.identity.UserId;
import java.util.Optional;

public interface RiskProfileRepository {
    Optional<RiskProfile> findLatestByOwner(UserId owner);
    void save(RiskProfile profile);
}
