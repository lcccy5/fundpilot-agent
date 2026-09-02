package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.identity.UserId;
import java.util.List;
import java.util.Optional;

public interface WatchlistRepository {
    List<WatchlistGroup> findByOwner(UserId owner);
    Optional<WatchlistGroup> findByIdAndOwner(String groupId,UserId owner);
    void saveGroup(WatchlistGroup group);
    boolean updateGroup(WatchlistGroup group,long expectedVersion);
    boolean deleteGroup(UserId owner,String groupId,long expectedVersion);
    void saveItem(UserId owner,String groupId,WatchlistItem item);
    boolean updateItem(UserId owner,String groupId,WatchlistItem item,long expectedVersion);
    boolean deleteItem(UserId owner,String groupId,String itemId,long expectedVersion);
}
