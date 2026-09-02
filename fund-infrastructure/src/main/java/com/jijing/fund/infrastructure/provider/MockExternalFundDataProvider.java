package com.jijing.fund.infrastructure.provider;

import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "fund.provider", name = "type", havingValue = "mock", matchIfMissing = true)
public class MockExternalFundDataProvider implements ExternalFundDataProvider {
    private static final Instant COLLECTED_AT = Instant.parse("2026-01-31T07:00:00Z");
    private static final Map<String, FundProfile> PROFILES = Map.of(
            "000001", profile("000001", "华夏成长混合", "混合型", "华夏基金", "王明"),
            "110022", profile("110022", "易方达消费行业股票", "股票型", "易方达基金", "张坤"));

    @Override public Optional<FundProfile> fetchProfile(FundCode code) { return Optional.ofNullable(PROFILES.get(code.value())); }
    @Override public List<NavPoint> fetchNavHistory(FundCode code, LocalDate start, LocalDate end) {
        if (!PROFILES.containsKey(code.value())) return List.of();
        return List.of(point(code, "2026-01-02", "1.0120"), point(code, "2026-01-05", "1.0180"),
                point(code, "2026-01-06", "1.0090"), point(code, "2026-01-07", "1.0240"),
                point(code, "2026-01-08", "1.0300")).stream()
                .filter(item -> !item.navDate().isBefore(start) && !item.navDate().isAfter(end)).toList();
    }
    @Override public String sourceName() { return "mock"; }
    private static FundProfile profile(String code, String name, String type, String company, String manager) {
        return new FundProfile(new FundCode(code), name, type, company, manager, LocalDate.of(2001, 12, 18),
                "mock", COLLECTED_AT, COLLECTED_AT);
    }
    private static NavPoint point(FundCode code, String date, String nav) {
        return new NavPoint(code, LocalDate.parse(date), new BigDecimal(nav), new BigDecimal(nav), null,
                NavStatus.CONFIRMED, "mock", COLLECTED_AT, COLLECTED_AT);
    }
}
