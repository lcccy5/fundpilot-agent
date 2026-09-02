package com.jijing.fund.domain.lock;

import com.jijing.fund.domain.model.FundCode;

public interface FundSyncLock {
    boolean tryLock(FundCode code);
    void unlock(FundCode code);
}

