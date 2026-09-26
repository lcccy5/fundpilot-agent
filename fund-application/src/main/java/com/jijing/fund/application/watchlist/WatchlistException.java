package com.jijing.fund.application.watchlist;

/**
 * 自选用例拒绝当前操作时抛出的异常。找不到和冲突是它的子类。空消息会被原样保存。
 */
public class WatchlistException extends RuntimeException {
    /**
     * 用给定说明构造异常。说明为空时消息为空。
     */
    public WatchlistException(String message) {
        super(message);
    }
}
