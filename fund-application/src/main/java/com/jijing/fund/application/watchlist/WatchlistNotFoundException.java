package com.jijing.fund.application.watchlist;

/**
 * 当前用户名下不存在请求的分组或条目。其他用户的分组也表现为找不到。删除分组的失败不使用本异常。
 */
public class WatchlistNotFoundException extends WatchlistException {
    /**
     * 用给定说明构造找不到异常。说明为空时消息为空。
     */
    public WatchlistNotFoundException(String message) {
        super(message);
    }
}
