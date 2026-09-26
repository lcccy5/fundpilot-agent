package com.jijing.fund.domain.research.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 验证 {@link ProviderId} 的规范化和格式约束（小写字母开头、2~63 位、仅小写字母数字和连字符）。 */
class ProviderIdTest {
    /** 首尾空白被去除、大写被转为小写。 */
    @Test
    void normalizesCaseAndWhitespace() {
        assertThat(new ProviderId("  Tencent-Quote ").value()).isEqualTo("tencent-quote");
    }

    /** null 值抛出 NullPointerException。 */
    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new ProviderId(null)).isInstanceOf(NullPointerException.class);
    }

    /** 空串、纯空白、单字符、数字开头、含下划线或空格的值被拒绝。 */
    @Test
    void rejectsIllegalFormats() {
        for (String value : new String[] {"", "   ", "a", "1quote", "tencent_quote", "tencent quote"}) {
            assertThatThrownBy(() -> new ProviderId(value))
                    .as("value [%s]", value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 长度上限为 63 位，64 位被拒绝。 */
    @Test
    void enforcesMaximumLength() {
        String longest = "a" + "b".repeat(62);

        assertThat(new ProviderId(longest).value()).hasSize(63);
        assertThatThrownBy(() -> new ProviderId(longest + "c")).isInstanceOf(IllegalArgumentException.class);
    }
}
