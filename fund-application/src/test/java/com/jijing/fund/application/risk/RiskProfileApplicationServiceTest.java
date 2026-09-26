package com.jijing.fund.application.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.risk.RiskLevel;
import com.jijing.fund.domain.risk.RiskProfile;
import com.jijing.fund.domain.risk.RiskProfileRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 风险等级只由答案计分得出。调用方不能通过版本或额外字段指定等级。
 */
class RiskProfileApplicationServiceTest {
    /**
     * 六题都是 5 分时总分为 30，等级为进取。不受支持的问卷版本在保存前被拒绝。
     */
    @Test
    void scoresDeterministicallyAndDoesNotTrustClientLevel() {
        var repository = new InMemory();
        var user = new AuthenticatedUser(new UserId("00000000-0000-0000-0000-000000000009"), Set.of(UserRole.USER), "s");
        var service = new RiskProfileApplicationService(repository, Clock.systemUTC());
        var answers = new LinkedHashMap<String, Integer>();
        answers.put("horizon", 5);
        answers.put("income", 5);
        answers.put("drawdown", 5);
        answers.put("liquidity", 5);
        answers.put("experience", 5);
        answers.put("lossAttitude", 5);
        var profile = service.submit(user, RiskProfileApplicationService.VERSION, answers);
        assertThat(profile.score()).isEqualTo(30);
        assertThat(profile.level()).isEqualTo(RiskLevel.AGGRESSIVE);
        assertThatThrownBy(() -> service.submit(user, "v0", answers)).isInstanceOf(RiskProfileException.class);
    }

    /**
     * 只保留最近一份档案。空仓库在读取时返回空，由用例转换成找不到。
     */
    private static final class InMemory implements RiskProfileRepository {
        private RiskProfile latest;

        /**
         * 返回最近一份档案，不按用户再过滤。这个测试只使用一个用户。
         */
        @Override
        public Optional<RiskProfile> findLatestByOwner(UserId owner) {
            return Optional.ofNullable(latest);
        }

        /**
         * 覆盖最近一份档案。档案为空时下一次读取会抛出空指针异常。
         */
        @Override
        public void save(RiskProfile profile) {
            latest = profile;
        }
    }
}
