package com.jijing.fund.domain.risk;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link RiskProfile} 拒绝缺少任一关键字段的测评结果。 */
class RiskProfileTest {
    private static final UserId OWNER = UserId.random();
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 测评标识、所属用户、问卷版本、答案指纹或风险等级为 null 时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new RiskProfile(null, OWNER, "q1", "hash", 10, RiskLevel.BALANCED, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("risk profile is invalid");
        assertThatThrownBy(() -> new RiskProfile("p1", null, "q1", "hash", 10, RiskLevel.BALANCED, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("risk profile is invalid");
        assertThatThrownBy(() -> new RiskProfile("p1", OWNER, null, "hash", 10, RiskLevel.BALANCED, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("risk profile is invalid");
        assertThatThrownBy(() -> new RiskProfile("p1", OWNER, "q1", null, 10, RiskLevel.BALANCED, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("risk profile is invalid");
        assertThatThrownBy(() -> new RiskProfile("p1", OWNER, "q1", "hash", 10, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("risk profile is invalid");
    }
}
