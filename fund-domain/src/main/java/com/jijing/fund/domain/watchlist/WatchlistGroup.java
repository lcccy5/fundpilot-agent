package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import java.util.List;

/** 用户的一个自选基金分组，包含展示名、排序、用于乐观锁的版本号以及组内的自选条目。 */
public record WatchlistGroup(String groupId, UserId ownerUserId, String displayName, int sortOrder, long version,
                             List<WatchlistItem> items, Instant createdAt, Instant updatedAt) {
    /**
     * 校验分组并规范化条目列表。
     * groupId 或 displayName 为 null/空白时抛出 IllegalArgumentException（"watchlist group is invalid"）；
     * items 为 null 视为空列表，否则拷贝为不可变列表，含 null 元素时抛出 NullPointerException。ownerUserId 不校验，可为 null。
     */
    public WatchlistGroup {
        if (groupId == null || groupId.isBlank() || displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("watchlist group is invalid");
        }
        items = items == null ? List.of() : List.copyOf(items);
    }
}
