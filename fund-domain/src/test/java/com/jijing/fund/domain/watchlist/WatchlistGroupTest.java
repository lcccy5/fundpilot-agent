package com.jijing.fund.domain.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.model.FundCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 {@link WatchlistGroup} 的必填校验和条目列表的规范化。 */
class WatchlistGroupTest {
    private static final WatchlistItem ITEM = new WatchlistItem("i1", new FundCode("000001"), null, null, 0, 0,
            Instant.EPOCH, Instant.EPOCH);

    /** 用给定标识、名称和条目构造分组，其余字段取合法默认值。 */
    private static WatchlistGroup group(String id, String name, List<WatchlistItem> items) {
        return new WatchlistGroup(id, UserId.random(), name, 0, 0, items, Instant.EPOCH, Instant.EPOCH);
    }

    /** 分组标识或展示名为 null/空白时抛出 IllegalArgumentException。 */
    @Test
    void rejectsMissingIdOrName() {
        assertThatThrownBy(() -> group(null, "科技", null)).isInstanceOf(IllegalArgumentException.class).hasMessage("watchlist group is invalid");
        assertThatThrownBy(() -> group(" ", "科技", null)).isInstanceOf(IllegalArgumentException.class).hasMessage("watchlist group is invalid");
        assertThatThrownBy(() -> group("g1", null, null)).isInstanceOf(IllegalArgumentException.class).hasMessage("watchlist group is invalid");
        assertThatThrownBy(() -> group("g1", "", null)).isInstanceOf(IllegalArgumentException.class).hasMessage("watchlist group is invalid");
    }

    /** 条目为 null 时视为空列表；非 null 时做不可变拷贝，之后修改原列表不影响分组。 */
    @Test
    void normalizesItems() {
        assertThat(group("g1", "科技", null).items()).isEmpty();

        List<WatchlistItem> items = new ArrayList<>(List.of(ITEM));
        var group = group("g1", "科技", items);
        items.clear();

        assertThat(group.items()).containsExactly(ITEM);
        assertThatThrownBy(() -> group.items().add(ITEM)).isInstanceOf(UnsupportedOperationException.class);
    }

    /** 条目列表含 null 元素时抛出 NullPointerException。 */
    @Test
    void rejectsNullItemElement() {
        assertThatThrownBy(() -> group("g1", "科技", Arrays.asList(ITEM, null))).isInstanceOf(NullPointerException.class);
    }
}
