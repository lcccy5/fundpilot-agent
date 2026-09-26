package com.jijing.fund.domain.lock;

import com.jijing.fund.domain.model.FundCode;

/**
 * 基金数据同步的互斥锁端口，保证同一只基金在同一时刻只有一个同步任务在执行；
 * 可以是进程内锁，也可以是分布式锁。code 为 null 时实现通常抛出 NullPointerException。
 */
public interface FundSyncLock {
    /**
     * 尝试立即获取某基金的同步锁，不阻塞等待。
     * 成功返回 true；锁已被持有（包括同一调用方重复获取，是否可重入由实现决定）时返回 false。
     */
    boolean tryLock(FundCode code);

    /** 释放某基金的同步锁；锁未被持有时应静默忽略，因此可以在 finally 中重复调用。 */
    void unlock(FundCode code);
}
