package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundSyncResult;
import java.time.LocalDate;

public interface FundSyncUseCase {
    FundSyncResult syncFund(String fundCode, LocalDate startDate, LocalDate endDate);
}
