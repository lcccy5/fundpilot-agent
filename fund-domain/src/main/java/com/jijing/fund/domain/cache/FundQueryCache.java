package com.jijing.fund.domain.cache;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.*;

public interface FundQueryCache {
    Optional<FundProfile> getProfile(FundCode code);
    void putProfile(FundProfile profile);
    Optional<List<NavPoint>> getHistory(FundCode code, LocalDate startDate, LocalDate endDate);
    void putHistory(FundCode code, LocalDate startDate, LocalDate endDate, List<NavPoint> points);
    void evict(FundCode code);
}

