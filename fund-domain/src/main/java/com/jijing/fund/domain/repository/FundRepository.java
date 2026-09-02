package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.*;
import java.util.*;
import java.time.LocalDate;

public interface FundRepository {
    Optional<FundProfile> findByCode(FundCode fundCode);
    void save(FundProfile profile);
    List<FundCode> findEnabledFundCodes(int offset, int limit);
    long getDataRevision(FundCode fundCode);
    void incrementDataRevision(FundCode fundCode, LocalDate latestNavDate);
}
