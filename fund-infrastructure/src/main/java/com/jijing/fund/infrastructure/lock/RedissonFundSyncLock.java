package com.jijing.fund.infrastructure.lock;

import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redisson 基金同步锁。等待时间为 0，租约 60 秒，拿不到立即返回 false。
 * 等待被中断时恢复中断标记并返回 false。连接失败和其他 Redisson 异常不捕获，直接抛出。
 * 只有当前线程持有时才解锁，避免误放别人的锁。没有空响应或重复提交分支。
 */
@Component
@ConditionalOnProperty(prefix = "fund.sync", name = "distributed-lock-enabled", havingValue = "true")
public class RedissonFundSyncLock implements FundSyncLock {
    private final RedissonClient client;

    /** 客户端由 {@link RedissonConfiguration} 提供。 */
    public RedissonFundSyncLock(RedissonClient client) {
        this.client = client;
    }

    /** 不等待。租约到期后锁会自动放开，即使持有方没有调用解锁。 */
    @Override
    public boolean tryLock(FundCode code) {
        try {
            return lock(code).tryLock(0, 60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 当前线程未持有时不调用 unlock，防止 Redisson 抛出非法监控状态。 */
    @Override
    public void unlock(FundCode code) {
        RLock lock = lock(code);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /** 锁名带基金代码，不同基金互不影响。 */
    private RLock lock(FundCode code) {
        return client.getLock("jijing:fund:sync-lock:" + code.value());
    }
}
