package com.jijing.fund.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 验证 {@link FundProfile} 的必填项校验以及可选字段允许缺失。 */
class FundProfileTest {
    private static final FundCode CODE = new FundCode("000001");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    /** 基金代码、名称、数据来源、采集时间任一为 null 时抛出 NullPointerException，消息指明缺失字段。 */
    @Test
    void rejectsMissingRequiredFields() {
        assertThatThrownBy(() -> new FundProfile(null, "华夏成长", null, null, null, null, "mock", null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("code");
        assertThatThrownBy(() -> new FundProfile(CODE, null, null, null, null, null, "mock", null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("name");
        assertThatThrownBy(() -> new FundProfile(CODE, "华夏成长", null, null, null, null, null, null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("dataSource");
        assertThatThrownBy(() -> new FundProfile(CODE, "华夏成长", null, null, null, null, "mock", null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("collectedAt");
    }

    /** 名称为空串或纯空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new FundProfile(CODE, "", null, null, null, null, "mock", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FundProfile(CODE, "   ", null, null, null, null, "mock", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 类型、管理人、基金经理、成立日期和来源更新时间都可以缺失。 */
    @Test
    void allowsOptionalFieldsToBeNull() {
        var profile = new FundProfile(CODE, "华夏成长", null, null, null, null, "mock", null, NOW);

        assertThat(profile.fundType()).isNull();
        assertThat(profile.establishedDate()).isNull();
    }
}
