package com.jijing.fund.infrastructure.lock;

import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 单进程同步锁，分布式锁关闭或未配置时使用。没有超时和连接失败。
 * 同一代码第二次获取返回 false，直到释放。释放未持有的代码是空操作。
 */
@Component
@ConditionalOnProperty(prefix = "fund.sync", name = "distributed-lock-enabled", havingValue = "false",
        matchIfMissing = true)
public class LocalFundSyncLock implements FundSyncLock {
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /** 集合新增成功才表示这次拿到了锁。 */
    @Override
    public boolean tryLock(FundCode code) {
        return held.add(code.value());
    }

    /** 只按代码移除，不检查持有线程。 */
    @Override
    public void unlock(FundCode code) {
        held.remove(code.value());
    }
}
