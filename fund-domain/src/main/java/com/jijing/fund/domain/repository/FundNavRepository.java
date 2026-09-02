package com.jijing.fund.domain.repository;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.*;

public interface FundNavRepository {
    List<NavPoint> findHistory(FundCode code, LocalDate startDate, LocalDate endDate);
    Optional<NavPoint> findLatest(FundCode code);
    int upsertBatch(List<NavPoint> navPoints);
}

