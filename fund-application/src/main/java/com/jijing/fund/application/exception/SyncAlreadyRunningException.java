package com.jijing.fund.application.exception;

/**
 * 表示同一基金已有同步持有互斥锁，本次不能开始。
 * 抛出时不会写入审计，也不会释放别人持有的锁。
 */
public class SyncAlreadyRunningException extends RuntimeException {

    /**
     * 用基金代码说明哪一只基金的同步仍在进行。
     * 代码会写入固定消息，本类型不再校验代码格式。
     *
     * @param code 已解析的基金代码文本
     */
    public SyncAlreadyRunningException(String code) {
        super("Sync already running for fund: " + code);
    }
}
