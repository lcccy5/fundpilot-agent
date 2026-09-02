package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundComparisonResult;
import java.time.LocalDate;
import java.util.List;

public interface FundComparisonUseCase {
    FundComparisonResult compare(List<String> fundCodes, LocalDate startDate, LocalDate endDate, String navBasis);
}

