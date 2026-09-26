package com.jijing.fund.application.watchlist;

import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.watchlist.WatchlistGroup;
import java.util.List;

/**
 * 自选分组的应用边界。实现必须只返回和修改当前用户的分组；其他用户的分组不能被读到或改掉。
 * 用户为空属于调用错误。名称、备注过长或版本冲突时应失败，不能静默覆盖。
 */
public interface WatchlistUseCase {
    /**
     * 列出当前用户的分组。用户为空时失败。
     */
    List<WatchlistGroup> list(AuthenticatedUser actor);

    /**
     * 创建分组。名称为空或过长时失败。
     */
    WatchlistGroup create(AuthenticatedUser actor, String name);

    /**
     * 按版本改名。分组不存在或版本已变化时失败。
     */
    WatchlistGroup rename(AuthenticatedUser actor, String groupId, String name, long version);

    /**
     * 按版本删除分组。版本不匹配时失败，不能删掉已经变化的分组。
     */
    void delete(AuthenticatedUser actor, String groupId, long version);

    /**
     * 向分组加入一只基金。分组不存在或基金代码不合法时失败；仓库判定重复时应失败而不是再插一条。
     */
    WatchlistGroup add(AuthenticatedUser actor, String groupId, String fundCode, String note, List<String> tags);

    /**
     * 修改条目的备注和标签。分组或条目不存在、版本已变化、备注过长时失败。
     */
    WatchlistGroup updateItem(AuthenticatedUser actor, String groupId, String itemId, String note, List<String> tags, long version);

    /**
     * 按版本删除条目。分组不存在或版本已变化时失败。
     */
    WatchlistGroup removeItem(AuthenticatedUser actor, String groupId, String itemId, long version);

    /**
     * 合并本地记住的基金代码。非法代码应计入拒绝而不是中断整批；空列表不应制造条目。
     */
    MergeLocalResult mergeLocal(AuthenticatedUser actor, List<String> fundCodes);
}
