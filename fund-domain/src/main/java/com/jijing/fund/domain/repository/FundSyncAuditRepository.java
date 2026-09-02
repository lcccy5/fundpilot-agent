package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.FundCode;
import java.time.*;

public interface FundSyncAuditRepository {
    long start(FundCode code, String source, LocalDate startDate, LocalDate endDate);
    void success(long id, int requestedCount, int savedCount, Instant finishedAt);
    void failure(long id, String errorCode, String safeMessage, Instant finishedAt);
}

