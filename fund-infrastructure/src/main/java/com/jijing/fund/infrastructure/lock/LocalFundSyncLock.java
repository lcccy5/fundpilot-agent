package com.jijing.fund.infrastructure.lock;

import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="fund.sync", name="distributed-lock-enabled", havingValue="false", matchIfMissing=true)
public class LocalFundSyncLock implements FundSyncLock {
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    @Override public boolean tryLock(FundCode code) { return held.add(code.value()); }
    @Override public void unlock(FundCode code) { held.remove(code.value()); }
}

