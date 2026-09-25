package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
import com.jijing.fund.application.dto.FundSearchHit;
import java.time.LocalDate;
import java.util.List;

public interface FundQueryUseCase {
    FundProfileResult getProfile(String fundCode);
    FundNavHistoryResult getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate);
    List<FundSearchHit> searchByName(String name);
}

