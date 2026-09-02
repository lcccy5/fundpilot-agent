package com.jijing.fund.domain.provider;

import com.jijing.fund.domain.model.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 外部基金数据采集端口。第三方 DTO 不得越过 Infrastructure 边界。 */
public interface ExternalFundDataProvider {
    Optional<FundProfile> fetchProfile(FundCode fundCode);
    List<NavPoint> fetchNavHistory(FundCode fundCode, LocalDate startDate, LocalDate endDate);
    String sourceName();
}

