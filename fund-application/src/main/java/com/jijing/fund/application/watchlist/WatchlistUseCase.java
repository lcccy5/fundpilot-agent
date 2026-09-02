package com.jijing.fund.application.watchlist;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.watchlist.*;
import java.util.List;
public interface WatchlistUseCase {
    List<WatchlistGroup> list(AuthenticatedUser actor);
    WatchlistGroup create(AuthenticatedUser actor,String name);
    WatchlistGroup rename(AuthenticatedUser actor,String groupId,String name,long version);
    void delete(AuthenticatedUser actor,String groupId,long version);
    WatchlistGroup add(AuthenticatedUser actor,String groupId,String fundCode,String note,List<String> tags);
    WatchlistGroup updateItem(AuthenticatedUser actor,String groupId,String itemId,String note,List<String> tags,long version);
    WatchlistGroup removeItem(AuthenticatedUser actor,String groupId,String itemId,long version);
    MergeLocalResult mergeLocal(AuthenticatedUser actor,List<String> fundCodes);
}
