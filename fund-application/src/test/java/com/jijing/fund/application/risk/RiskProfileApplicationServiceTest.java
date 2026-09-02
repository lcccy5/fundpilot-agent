package com.jijing.fund.application.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.risk.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RiskProfileApplicationServiceTest {
    @Test void scoresDeterministicallyAndDoesNotTrustClientLevel(){
        var repo=new InMemory();
        var user=new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000009"),Set.of(UserRole.USER),"s");
        var service=new RiskProfileApplicationService(repo,Clock.systemUTC());
        var answers=new LinkedHashMap<String,Integer>();
        answers.put("horizon",5);answers.put("income",5);answers.put("drawdown",5);answers.put("liquidity",5);answers.put("experience",5);answers.put("lossAttitude",5);
        var profile=service.submit(user,RiskProfileApplicationService.VERSION,answers);
        assertThat(profile.score()).isEqualTo(30);
        assertThat(profile.level()).isEqualTo(RiskLevel.AGGRESSIVE);
        assertThatThrownBy(()->service.submit(user,"v0",answers)).isInstanceOf(RiskProfileException.class);
    }
    private static final class InMemory implements RiskProfileRepository {
        private RiskProfile latest;
        @Override public Optional<RiskProfile> findLatestByOwner(UserId owner){return Optional.ofNullable(latest);}
        @Override public void save(RiskProfile profile){latest=profile;}
    }
}
