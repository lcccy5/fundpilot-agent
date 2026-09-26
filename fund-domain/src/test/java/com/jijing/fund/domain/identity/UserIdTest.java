package com.jijing.fund.domain.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证 {@link UserId} 对缺失值和非 UUID 值的拒绝，以及随机生成的可用性。 */
class UserIdTest {
    /** null、空串和纯空白都抛出 IllegalArgumentException，并提示标识必填。 */
    @Test
    void rejectsMissingValue() {
        assertThatThrownBy(() -> new UserId(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("userId is required");
        assertThatThrownBy(() -> new UserId("")).isInstanceOf(IllegalArgumentException.class).hasMessage("userId is required");
        assertThatThrownBy(() -> new UserId("   ")).isInstanceOf(IllegalArgumentException.class).hasMessage("userId is required");
    }

    /** 无法解析为 UUID 的字符串抛出 IllegalArgumentException。 */
    @Test
    void rejectsNonUuidValue() {
        assertThatThrownBy(() -> new UserId("alice")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 多次调用 random() 得到的标识互不相同，且都能被重新构造。 */
    @Test
    void randomProducesDistinctValidIds() {
        UserId first = UserId.random();
        UserId second = UserId.random();

        assertThat(first).isNotEqualTo(second);
        assertThat(new UserId(first.value())).isEqualTo(first);
    }
}
