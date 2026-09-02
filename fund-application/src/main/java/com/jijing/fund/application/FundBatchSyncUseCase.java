package com.jijing.fund.application;

import com.jijing.fund.application.dto.BatchSyncResult;
import java.time.LocalDate;

public interface FundBatchSyncUseCase {
    BatchSyncResult syncEnabledFunds(LocalDate startDate, LocalDate endDate);
}

