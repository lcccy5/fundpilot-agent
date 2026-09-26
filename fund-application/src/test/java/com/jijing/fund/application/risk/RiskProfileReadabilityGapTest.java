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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 风险问卷的非法答案、缺失档案，以及两个用户的档案互不可见。
 * 非法答案必须在保存之前失败。
 */
class RiskProfileReadabilityGapTest {
    private final UserId ownerId = new UserId("00000000-0000-0000-0000-0000000000e1");
    private final UserId otherId = new UserId("00000000-0000-0000-0000-0000000000f2");
    private final AuthenticatedUser owner = new AuthenticatedUser(ownerId, Set.of(UserRole.USER), "owner");
    private final AuthenticatedUser other = new AuthenticatedUser(otherId, Set.of(UserRole.USER), "other");

    /**
     * 还没有提交过时，读取当前档案按找不到拒绝。
     */
    @Test
    void missingProfileIsRejected() {
        var repository = new Memory();
        var service = new RiskProfileApplicationService(repository, Clock.systemUTC());
        assertThatThrownBy(() -> service.current(owner))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("risk profile not found");
        assertThat(repository.findLatestByOwner(ownerId)).isEmpty();
    }

    /**
     * 空答案、缺题、越界分值和不受支持的版本都拒绝保存。多余的键不能把分数抬高。
     */
    @Test
    void illegalAnswersAreRejectedWithoutSaving() {
        var repository = new Memory();
        var service = new RiskProfileApplicationService(repository, Clock.systemUTC());
        var answers = ones();

        assertThatThrownBy(() -> service.submit(owner, RiskProfileApplicationService.VERSION, null))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("answers are required");
        assertThatThrownBy(() -> service.submit(owner, null, answers))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("unsupported questionnaire version");
        assertThatThrownBy(() -> service.submit(owner, "v0", answers))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("unsupported questionnaire version");

        answers.remove("income");
        assertThatThrownBy(() -> service.submit(owner, RiskProfileApplicationService.VERSION, answers))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("answer required: income");

        var low = ones();
        low.put("drawdown", 0);
        assertThatThrownBy(() -> service.submit(owner, RiskProfileApplicationService.VERSION, low))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("answer required: drawdown");

        var high = ones();
        high.put("horizon", 6);
        assertThatThrownBy(() -> service.submit(owner, RiskProfileApplicationService.VERSION, high))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("answer required: horizon");

        var missingValue = ones();
        missingValue.put("liquidity", null);
        assertThatThrownBy(() -> service.submit(owner, RiskProfileApplicationService.VERSION, missingValue))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("answer required: liquidity");
        assertThat(repository.findLatestByOwner(ownerId)).isEmpty();

        var extra = ones();
        extra.put("clientLevel", 5);
        var profile = service.submit(owner, RiskProfileApplicationService.VERSION, extra);
        assertThat(profile.score()).isEqualTo(6);
        assertThat(profile.level()).isEqualTo(RiskLevel.CONSERVATIVE);
    }

    /**
     * 一个用户的档案不能被另一个用户读到，后提交的低分也不会覆盖先提交的高分。
     */
    @Test
    void profilesStayIsolatedBetweenUsers() {
        var service = new RiskProfileApplicationService(new Memory(), Clock.systemUTC());
        var aggressive = service.submit(owner, RiskProfileApplicationService.VERSION, fives());
        assertThatThrownBy(() -> service.current(other))
                .isInstanceOf(RiskProfileException.class)
                .hasMessage("risk profile not found");

        var conservative = service.submit(other, RiskProfileApplicationService.VERSION, ones());
        assertThat(service.current(owner).profileId()).isEqualTo(aggressive.profileId());
        assertThat(service.current(owner).level()).isEqualTo(RiskLevel.AGGRESSIVE);
        assertThat(conservative.level()).isEqualTo(RiskLevel.CONSERVATIVE);
        assertThat(service.current(other).ownerUserId()).isEqualTo(otherId);
    }

    /**
     * 六题都答 1。调用方随后改掉某一题时，其余题仍是合法分值。
     */
    private static LinkedHashMap<String, Integer> ones() {
        return answers(1);
    }

    /**
     * 六题都答 5，用来得到进取等级。
     */
    private static LinkedHashMap<String, Integer> fives() {
        return answers(5);
    }

    /**
     * 按问卷题号填入同一分值。分值超出 1 到 5 时，提交会被拒绝，这里不提前拦截。
     */
    private static LinkedHashMap<String, Integer> answers(int value) {
        var answers = new LinkedHashMap<String, Integer>();
        answers.put("horizon", value);
        answers.put("income", value);
        answers.put("drawdown", value);
        answers.put("liquidity", value);
        answers.put("experience", value);
        answers.put("lossAttitude", value);
        return answers;
    }

    /**
     * 按用户保存最近一份档案。不同用户的记录互不覆盖。
     */
    private static final class Memory implements RiskProfileRepository {
        private final Map<UserId, RiskProfile> latest = new HashMap<>();

        /**
         * 读取该用户的最近档案。没有提交过时返回空。
         */
        @Override
        public Optional<RiskProfile> findLatestByOwner(UserId owner) {
            return Optional.ofNullable(latest.get(owner));
        }

        /**
         * 用这份档案替换该用户之前的记录。档案为空时抛出空指针异常。
         */
        @Override
        public void save(RiskProfile profile) {
            latest.put(profile.ownerUserId(), profile);
        }
    }
}
