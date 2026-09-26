package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** 验证 {@link DerivationMetadata} 对版本信息和计算时间的必填校验，以及局限说明的规范化。 */
class DerivationMetadataTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 算法版本或规则版本为 null/空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingVersions() {
        assertThatThrownBy(() -> new DerivationMetadata(null, "r1", NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("algorithmVersion");
        assertThatThrownBy(() -> new DerivationMetadata(" ", "r1", NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("algorithmVersion");
        assertThatThrownBy(() -> new DerivationMetadata("a1", null, NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ruleVersion");
        assertThatThrownBy(() -> new DerivationMetadata("a1", "", NOW, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ruleVersion");
    }

    /** 计算时间为 null 时抛出 NullPointerException。 */
    @Test
    void rejectsMissingCalculatedAt() {
        assertThatThrownBy(() -> new DerivationMetadata("a1", "r1", null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("calculatedAt");
    }

    /** 局限说明去除 null 和空白项、去除首尾空白并去重；缺失时为空列表。 */
    @Test
    void normalizesLimitations() {
        var metadata = new DerivationMetadata("a1", "r1", NOW, Arrays.asList(" 样本不足 ", null, "", "样本不足"));

        assertThat(metadata.limitations()).containsExactly("样本不足");
        assertThat(new DerivationMetadata("a1", "r1", NOW, null).limitations()).isEmpty();
    }
}
