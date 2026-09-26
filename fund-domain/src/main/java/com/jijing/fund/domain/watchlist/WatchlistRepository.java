package com.jijing.fund.domain.watchlist;

import com.jijing.fund.domain.identity.UserId;
import java.util.List;
import java.util.Optional;

/**
 * 自选分组与自选条目的持久化端口。所有读写都按所属用户隔离，更新和删除使用版本号做乐观锁；
 * 返回 false 表示目标不存在、不属于该用户或版本已被并发修改，调用方应按冲突处理。接口不校验参数。
 */
public interface WatchlistRepository {
    /** 列出用户的全部自选分组（含条目）；没有分组时返回空列表。 */
    List<WatchlistGroup> findByOwner(UserId owner);

    /** 查找属于该用户的指定分组（含条目）；分组不存在或属于其他用户时都返回空。 */
    Optional<WatchlistGroup> findByIdAndOwner(String groupId, UserId owner);

    /** 新建分组，所属用户取自 group.ownerUserId()；分组标识或同一用户下的分组名重复时由实现抛出存储层异常。 */
    void saveGroup(WatchlistGroup group);

    /**
     * 在版本号等于 expectedVersion 时更新分组的名称、排序和版本，所属用户取自 group.ownerUserId()；
     * 成功返回 true，版本冲突或分组不属于该用户时返回 false。
     */
    boolean updateGroup(WatchlistGroup group, long expectedVersion);

    /** 在版本号等于 expectedVersion 时删除该用户的分组；重复删除或版本冲突时返回 false。 */
    boolean deleteGroup(UserId owner, String groupId, long expectedVersion);

    /**
     * 向该用户的指定分组添加条目。该方法没有返回值，分组不存在或不属于该用户时实现可能静默不写入，调用方需先校验归属；
     * 同一分组内重复添加同一基金时应抛出 IllegalArgumentException。
     */
    void saveItem(UserId owner, String groupId, WatchlistItem item);

    /** 在条目版本号等于 expectedVersion 时更新备注、标签、排序和版本；成功返回 true，冲突或不属于该用户时返回 false。 */
    boolean updateItem(UserId owner, String groupId, WatchlistItem item, long expectedVersion);

    /** 在条目版本号等于 expectedVersion 时删除该用户分组内的条目；重复删除或版本冲突时返回 false。 */
    boolean deleteItem(UserId owner, String groupId, String itemId, long expectedVersion);
}
