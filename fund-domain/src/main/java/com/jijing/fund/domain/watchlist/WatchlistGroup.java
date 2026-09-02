package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import java.util.List;

public record WatchlistGroup(String groupId,UserId ownerUserId,String displayName,int sortOrder,long version,
                             List<WatchlistItem> items,Instant createdAt,Instant updatedAt) {
    public WatchlistGroup { if(groupId==null||groupId.isBlank()||displayName==null||displayName.isBlank())throw new IllegalArgumentException("watchlist group is invalid"); items=items==null?List.of():List.copyOf(items); }
}
