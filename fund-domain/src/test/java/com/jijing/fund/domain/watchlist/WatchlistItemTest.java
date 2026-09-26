package com.jijing.fund.domain.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.model.FundCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link WatchlistItem} 对标签列表的规范化。 */
class WatchlistItemTest {
    /** 用给定标签构造一个自选条目，其余字段取合法默认值。 */
    private static WatchlistItem item(List<String> tags) {
        return new WatchlistItem("i1", new FundCode("000001"), "长期关注", tags, 0, 0, Instant.EPOCH, Instant.EPOCH);
    }

    /** 标签为 null 时视为没有标签。 */
    @Test
    void nullTagsBecomeEmpty() {
        assertThat(item(null).tags()).isEmpty();
    }

    /** 构造后修改原列表不影响条目，条目暴露的标签列表不可修改；重复标签原样保留。 */
    @Test
    void tagsAreDefensivelyCopiedWithoutDeduplication() {
        List<String> tags = new ArrayList<>(List.of("科技", "科技"));
        var item = item(tags);
        tags.add("医药");

        assertThat(item.tags()).containsExactly("科技", "科技");
        assertThatThrownBy(() -> item.tags().add("医药")).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 标签列表含 null 元素时抛出 NullPointerException。 */
    @Test
    void rejectsNullTagElement() {
        assertThatThrownBy(() -> item(Arrays.asList("科技", null))).isInstanceOf(NullPointerException.class);
    }
}
