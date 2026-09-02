package com.jijing.fund.infrastructure.lock;

import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import java.util.concurrent.TimeUnit;
import org.redisson.api.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.sync", name="distributed-lock-enabled", havingValue="true")
public class RedissonFundSyncLock implements FundSyncLock {
    private final RedissonClient client;
    public RedissonFundSyncLock(RedissonClient client) { this.client = client; }
    @Override public boolean tryLock(FundCode code) { try { return lock(code).tryLock(0, 60, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); return false; } }
    @Override public void unlock(FundCode code) { RLock lock = lock(code); if (lock.isHeldByCurrentThread()) lock.unlock(); }
    private RLock lock(FundCode code) { return client.getLock("jijing:fund:sync-lock:" + code.value()); }
}

