package com.jijing.fund.application.watchlist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.watchlist.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class WatchlistIsolationTest {
    @Test void userBCannotReadUserAGroup(){
        var now=Instant.EPOCH;
        var owner=new UserId("00000000-0000-0000-0000-00000000000a");
        var other=new UserId("00000000-0000-0000-0000-00000000000b");
        var repo=new Fake();
        repo.saveGroup(new WatchlistGroup("g1",owner,"A",0,0,List.of(),now,now));
        var service=new WatchlistApplicationService(repo,Clock.systemUTC());
        var userB=new AuthenticatedUser(other,Set.of(UserRole.USER),"s");
        assertThatThrownBy(()->service.add(userB,"g1","000001",null,List.of())).isInstanceOf(WatchlistNotFoundException.class);
    }
    private static final class Fake implements WatchlistRepository {
        private WatchlistGroup group;
        @Override public List<WatchlistGroup> findByOwner(UserId owner){return group!=null&&group.ownerUserId().equals(owner)?List.of(group):List.of();}
        @Override public Optional<WatchlistGroup> findByIdAndOwner(String groupId,UserId owner){return group!=null&&group.groupId().equals(groupId)&&group.ownerUserId().equals(owner)?Optional.of(group):Optional.empty();}
        @Override public void saveGroup(WatchlistGroup g){group=g;}
        @Override public boolean updateGroup(WatchlistGroup g,long expectedVersion){return false;}
        @Override public boolean deleteGroup(UserId owner,String groupId,long expectedVersion){return false;}
        @Override public void saveItem(UserId owner,String groupId,WatchlistItem item){}
        @Override public boolean updateItem(UserId owner,String groupId,WatchlistItem item,long expectedVersion){return false;}
        @Override public boolean deleteItem(UserId owner,String groupId,String itemId,long expectedVersion){return false;}
    }
}
