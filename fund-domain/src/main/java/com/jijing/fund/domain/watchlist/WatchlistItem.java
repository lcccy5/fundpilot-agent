package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.model.FundCode;
import java.time.Instant;
import java.util.List;

/** 自选分组中的一只基金，包含备注、标签、排序和用于乐观锁的版本号。 */
public record WatchlistItem(String itemId, FundCode fundCode, String note, List<String> tags, int sortOrder, long version,
                            Instant createdAt, Instant updatedAt) {
    /**
     * 规范化标签：null 视为没有标签，否则拷贝为不可变列表，含 null 元素时抛出 NullPointerException；标签不去重也不去空白。
     * itemId、fundCode 等其余字段不校验，可为 null。
     */
    public WatchlistItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
