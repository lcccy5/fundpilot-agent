package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.model.FundCode;
import java.time.Instant;
import java.util.List;

public record WatchlistItem(String itemId,FundCode fundCode,String note,List<String> tags,int sortOrder,long version,
                            Instant createdAt,Instant updatedAt) {
    public WatchlistItem { tags=tags==null?List.of():List.copyOf(tags); }
}
