package com.jijing.fund.application;

import com.jijing.fund.application.dto.BatchSyncResult;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.repository.FundRepository;
import java.time.LocalDate;
import java.util.List;

/** 单独的批量编排器，确保每个 syncFund 都通过 Spring 事务代理执行。 */
public class FundBatchSyncApplicationService implements FundBatchSyncUseCase {
    private final FundRepository repository; private final FundSyncUseCase singleSync;
    public FundBatchSyncApplicationService(FundRepository repository, FundSyncUseCase singleSync) {
        this.repository = repository; this.singleSync = singleSync;
    }
    @Override public BatchSyncResult syncEnabledFunds(LocalDate startDate, LocalDate endDate) {
        int offset = 0, total = 0, succeeded = 0, failed = 0;
        while (true) {
            List<FundCode> codes = repository.findEnabledFundCodes(offset, 100);
            if (codes.isEmpty()) break;
            for (FundCode code : codes) {
                total++;
                try { singleSync.syncFund(code.value(), startDate, endDate); succeeded++; }
                catch (RuntimeException ex) { failed++; }
            }
            if (codes.size() < 100) break;
            offset += codes.size();
        }
        return new BatchSyncResult(total, succeeded, failed);
    }
}

