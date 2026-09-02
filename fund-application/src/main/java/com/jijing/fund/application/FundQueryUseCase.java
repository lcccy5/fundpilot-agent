package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
import java.time.LocalDate;

public interface FundQueryUseCase {
    FundProfileResult getProfile(String fundCode);
    FundNavHistoryResult getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);
}

