package com.jijing.fund.application.watchlist;

/**
 * 分组或条目的版本与这次修改冲突，或仓库拒绝了重复写入。它是自选异常的一种，需要和普通校验失败区分时应按类型捕获。
 */
public class WatchlistConflictException extends WatchlistException {
    /**
     * 用给定说明构造冲突异常。说明为空时消息为空。仓库给出的原因会原样放进消息。
     */
    public WatchlistConflictException(String message) {
        super(message);
    }
}
