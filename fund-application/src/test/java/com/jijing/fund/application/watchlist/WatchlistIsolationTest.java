package com.jijing.fund.application.watchlist;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.domain.watchlist.WatchlistItem;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 其他用户不能往不属于自己的自选分组里加基金。
 */
class WatchlistIsolationTest {
    /**
     * 分组只对属主可见时，其他用户追加基金得到找不到。
     */
    @Test
    void userBCannotReadUserAGroup() {
        var now = Instant.EPOCH;
        var owner = new UserId("00000000-0000-0000-0000-00000000000a");
        var other = new UserId("00000000-0000-0000-0000-00000000000b");
        var repository = new Fake();
        repository.saveGroup(new WatchlistGroup("g1", owner, "A", 0, 0, List.of(), now, now));
        var service = new WatchlistApplicationService(repository, Clock.systemUTC());
        var user = new AuthenticatedUser(other, Set.of(UserRole.USER), "s");
        assertThatThrownBy(() -> service.add(user, "g1", "000001", null, List.of())).isInstanceOf(WatchlistNotFoundException.class);
    }

    /**
     * 只记住一个分组，并按属主过滤。更新和删除固定返回失败，因为这个测试不会走到那些写入。
     */
    private static final class Fake implements WatchlistRepository {
        private WatchlistGroup group;

        /**
         * 属主匹配时返回该分组，否则返回空列表。
         */
        @Override
        public List<WatchlistGroup> findByOwner(UserId owner) {
            return group != null && group.ownerUserId().equals(owner) ? List.of(group) : List.of();
        }

        /**
         * 分组号和属主都匹配时返回分组，否则返回空。
         */
        @Override
        public Optional<WatchlistGroup> findByIdAndOwner(String groupId, UserId owner) {
            return group != null && group.groupId().equals(groupId) && group.ownerUserId().equals(owner) ? Optional.of(group) : Optional.empty();
        }

        /**
         * 记住这个分组，覆盖之前的一笔。分组为空时随后的读取会抛出空指针异常。
         */
        @Override
        public void saveGroup(WatchlistGroup saved) {
            group = saved;
        }

        /**
         * 这个测试不改名，固定拒绝更新。
         */
        @Override
        public boolean updateGroup(WatchlistGroup saved, long expectedVersion) {
            return false;
        }

        /**
         * 这个测试不删除分组，固定拒绝。
         */
        @Override
        public boolean deleteGroup(UserId owner, String groupId, long expectedVersion) {
            return false;
        }

        /**
         * 这个测试的追加发生在找不到分组之后，方法体不会被调用。传入任何条目都不保存。
         */
        @Override
        public void saveItem(UserId owner, String groupId, WatchlistItem item) {
        }

        /**
         * 这个测试不修改条目，固定拒绝。
         */
        @Override
        public boolean updateItem(UserId owner, String groupId, WatchlistItem item, long expectedVersion) {
            return false;
        }

        /**
         * 这个测试不删除条目，固定拒绝。
         */
        @Override
        public boolean deleteItem(UserId owner, String groupId, String itemId, long expectedVersion) {
            return false;
        }
    }
}
