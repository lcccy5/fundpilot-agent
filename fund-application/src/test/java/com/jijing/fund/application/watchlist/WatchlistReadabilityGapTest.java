package com.jijing.fund.application.watchlist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import com.jijing.fund.domain.watchlist.WatchlistItem;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 自选分组缺失、版本冲突、本地代码合并拒绝，以及用户之间的隔离。
 * 删除分组在仓库拒绝时表现为冲突，测试保持这个现状。
 */
class WatchlistReadabilityGapTest {
    private final UserId ownerId = new UserId("00000000-0000-0000-0000-0000000000c1");
    private final UserId otherId = new UserId("00000000-0000-0000-0000-0000000000d2");
    private final AuthenticatedUser owner = new AuthenticatedUser(ownerId, Set.of(UserRole.USER), "owner");
    private final AuthenticatedUser other = new AuthenticatedUser(otherId, Set.of(UserRole.USER), "other");

    /**
     * 属主查询一个不存在的分组时，改名、加基金、改条目和删条目都是找不到。删除分组没有单独的找不到结果。
     */
    @Test
    void missingWatchlistIsNotFoundExceptDelete() {
        var service = new WatchlistApplicationService(new Memory(), Clock.systemUTC());
        assertThatThrownBy(() -> service.rename(owner, "missing", "新名称", 0))
                .isInstanceOf(WatchlistNotFoundException.class)
                .hasMessage("watchlist not found");
        assertThatThrownBy(() -> service.add(owner, "missing", "000001", null, List.of()))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.updateItem(owner, "missing", "item", null, List.of(), 0))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.removeItem(owner, "missing", "item", 0))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.delete(owner, "missing", 0))
                .isInstanceOf(WatchlistConflictException.class)
                .hasMessage("watchlist was updated concurrently");
    }

    /**
     * 版本过期、重复基金和并不存在的条目删除都变成冲突，分组内容保持原样。
     */
    @Test
    void versionAndDuplicateConflictsDoNotOverwrite() {
        var memory = new Memory();
        var service = new WatchlistApplicationService(memory, Clock.systemUTC());
        var group = service.create(owner, "核心");
        var added = service.add(owner, group.groupId(), "000001", null, List.of());
        var itemId = added.items().getFirst().itemId();

        assertThatThrownBy(() -> service.rename(owner, group.groupId(), "改名", 9))
                .isInstanceOf(WatchlistConflictException.class)
                .hasMessage("watchlist was updated concurrently");
        assertThat(service.list(owner).getFirst().displayName()).isEqualTo("核心");
        assertThatThrownBy(() -> service.add(owner, group.groupId(), "000001", null, List.of()))
                .isInstanceOf(WatchlistConflictException.class)
                .hasMessage("duplicate fund");
        assertThatThrownBy(() -> service.removeItem(owner, group.groupId(), "absent", 0))
                .isInstanceOf(WatchlistConflictException.class)
                .hasMessage("watchlist item was updated concurrently");
        assertThat(service.list(owner).getFirst().items()).extracting(WatchlistItem::itemId).containsExactly(itemId);
    }

    /**
     * 空列表不新增条目。非法代码计入拒绝；重复的六位代码计入已存在，不会再次写入。
     */
    @Test
    void mergeLocalRejectsEmptyAndIllegalCodes() {
        var service = new WatchlistApplicationService(new Memory(), Clock.systemUTC());
        var empty = service.mergeLocal(owner, null);
        assertThat(empty.added()).isZero();
        assertThat(empty.rejected()).isZero();
        assertThat(service.list(owner)).extracting(WatchlistGroup::displayName).containsExactly("默认分组");

        var codes = new ArrayList<String>();
        codes.add("000001");
        codes.add("000001");
        codes.add("12");
        codes.add(null);
        codes.add("abcdef");
        var merged = service.mergeLocal(owner, codes);
        assertThat(merged.added()).isEqualTo(1);
        assertThat(merged.existing()).isEqualTo(1);
        assertThat(merged.rejected()).isEqualTo(3);
        assertThat(merged.rejectedCodes()).containsExactly("12", "", "abcdef");
        assertThat(service.list(owner).getFirst().items()).hasSize(1);
    }

    /**
     * 其他用户不能改属主的分组。删除会被拒绝并表现为冲突，合并只会在其他用户自己的名下新建分组。
     */
    @Test
    void otherUserCannotMutateOwnedWatchlist() {
        var memory = new Memory();
        var service = new WatchlistApplicationService(memory, Clock.systemUTC());
        var group = service.create(owner, "属主");
        service.add(owner, group.groupId(), "000001", "备注", List.of("核心"));

        assertThatThrownBy(() -> service.add(other, group.groupId(), "000002", null, List.of()))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.rename(other, group.groupId(), "抢走", 0))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.updateItem(other, group.groupId(), "item", null, List.of(), 0))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.removeItem(other, group.groupId(), "item", 0))
                .isInstanceOf(WatchlistNotFoundException.class);
        assertThatThrownBy(() -> service.delete(other, group.groupId(), 0))
                .isInstanceOf(WatchlistConflictException.class);
        assertThat(service.list(owner)).hasSize(1);
        assertThat(service.list(owner).getFirst().items()).hasSize(1);

        var merged = service.mergeLocal(other, List.of("000001"));
        assertThat(merged.groupId()).isNotEqualTo(group.groupId());
        assertThat(service.list(other)).extracting(WatchlistGroup::displayName).containsExactly("默认分组");
        assertThat(service.list(owner).getFirst().items()).hasSize(1);
    }

    /**
     * 内存中的自选仓库只按属主返回分组。重复基金抛出参数异常，供用例转换成冲突。
     */
    private static final class Memory implements WatchlistRepository {
        private final Map<String, WatchlistGroup> groups = new LinkedHashMap<>();

        /**
         * 返回该用户的分组。用户为空时抛出空指针异常。
         */
        @Override
        public List<WatchlistGroup> findByOwner(UserId owner) {
            return groups.values().stream().filter(group -> group.ownerUserId().equals(owner)).toList();
        }

        /**
         * 同时匹配分组号和属主。其他用户拿到空结果。
         */
        @Override
        public Optional<WatchlistGroup> findByIdAndOwner(String groupId, UserId owner) {
            var group = groups.get(groupId);
            if (group == null || !group.ownerUserId().equals(owner)) {
                return Optional.empty();
            }
            return Optional.of(group);
        }

        /**
         * 保存新分组。分组为空时抛出空指针异常。
         */
        @Override
        public void saveGroup(WatchlistGroup group) {
            groups.put(group.groupId(), group);
        }

        /**
         * 版本和属主都匹配时替换分组。否则返回 false，原分组不变。
         */
        @Override
        public boolean updateGroup(WatchlistGroup group, long expectedVersion) {
            var current = groups.get(group.groupId());
            if (current == null || current.version() != expectedVersion || !current.ownerUserId().equals(group.ownerUserId())) {
                return false;
            }
            groups.put(group.groupId(), group);
            return true;
        }

        /**
         * 版本和属主匹配时删除。不存在或不属于该用户时返回 false。
         */
        @Override
        public boolean deleteGroup(UserId owner, String groupId, long expectedVersion) {
            var current = groups.get(groupId);
            if (current == null || !current.ownerUserId().equals(owner) || current.version() != expectedVersion) {
                return false;
            }
            groups.remove(groupId);
            return true;
        }

        /**
         * 追加条目。同一分组已有该基金时抛出参数异常。分组不存在时同样抛出参数异常。
         */
        @Override
        public void saveItem(UserId owner, String groupId, WatchlistItem item) {
            var group = findByIdAndOwner(groupId, owner).orElseThrow(() -> new IllegalArgumentException("missing group"));
            if (group.items().stream().anyMatch(current -> current.fundCode().equals(item.fundCode()))) {
                throw new IllegalArgumentException("duplicate fund");
            }
            var items = new ArrayList<>(group.items());
            items.add(item);
            groups.put(groupId, copy(group, items));
        }

        /**
         * 按版本替换条目。条目不存在或版本不符时返回 false。
         */
        @Override
        public boolean updateItem(UserId owner, String groupId, WatchlistItem item, long expectedVersion) {
            var group = findByIdAndOwner(groupId, owner).orElse(null);
            if (group == null) {
                return false;
            }
            var items = new ArrayList<WatchlistItem>();
            boolean found = false;
            for (var current : group.items()) {
                if (current.itemId().equals(item.itemId())) {
                    if (current.version() != expectedVersion) {
                        return false;
                    }
                    items.add(item);
                    found = true;
                } else {
                    items.add(current);
                }
            }
            if (!found) {
                return false;
            }
            groups.put(groupId, copy(group, items));
            return true;
        }

        /**
         * 按版本删除条目。条目不存在或版本不符时返回 false，分组保持原样。
         */
        @Override
        public boolean deleteItem(UserId owner, String groupId, String itemId, long expectedVersion) {
            var group = findByIdAndOwner(groupId, owner).orElse(null);
            if (group == null) {
                return false;
            }
            var items = new ArrayList<WatchlistItem>();
            boolean found = false;
            for (var current : group.items()) {
                if (current.itemId().equals(itemId)) {
                    if (current.version() != expectedVersion) {
                        return false;
                    }
                    found = true;
                } else {
                    items.add(current);
                }
            }
            if (!found) {
                return false;
            }
            groups.put(groupId, copy(group, items));
            return true;
        }

        /**
         * 复制分组并替换条目列表，保留标识、名称和版本。
         */
        private static WatchlistGroup copy(WatchlistGroup group, List<WatchlistItem> items) {
            return new WatchlistGroup(group.groupId(), group.ownerUserId(), group.displayName(), group.sortOrder(), group.version(),
                    items, group.createdAt(), group.updatedAt());
        }
    }
}
