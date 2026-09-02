package com.jijing.fund.application;

import com.jijing.fund.application.dto.*;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.*;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Duration;
import java.util.ArrayList;

public class FundQueryApplicationService implements FundQueryUseCase {
    private final FundRepository fundRepository;
    private final FundNavRepository navRepository;
    private final FundQueryCache cache;
    private final ExternalFundDataProvider provider;
    private final Clock clock;

    public FundQueryApplicationService(FundRepository fundRepository, FundNavRepository navRepository,
            FundQueryCache cache, ExternalFundDataProvider provider, Clock clock) {
        this.fundRepository = fundRepository;
        this.navRepository = navRepository;
        this.cache = cache;
        this.provider = provider;
        this.clock = clock;
    }

    @Override public FundProfileResult getProfile(String fundCode) {
        var code = parseCode(fundCode);
        var profile = cache.getProfile(code).orElseGet(() -> loadProfile(code));
        return toResult(profile);
    }

    @Override public FundNavHistoryResult getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new InvalidFundQueryException("startDate and endDate are required and startDate must not be after endDate");
        }
        var code = parseCode(fundCode);
        var points = cache.getHistory(code, startDate, endDate).orElseGet(() -> loadHistory(code, startDate, endDate));
        if (fundRepository.findByCode(code).isEmpty()) throw new FundNotFoundException(fundCode);
        var items = new ArrayList<NavPointResult>(points.size());
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            var change = i == 0 ? null : point.unitNav().subtract(points.get(i - 1).unitNav())
                    .divide(points.get(i - 1).unitNav(), 6, RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100));
            items.add(new NavPointResult(point.navDate(), point.unitNav(), point.accumulatedNav(), change));
        }
        String source = points.isEmpty() ? "database" : points.get(0).dataSource();
        return new FundNavHistoryResult(code.value(), source, items);
    }

    private FundCode parseCode(String fundCode) {
        try { return new FundCode(fundCode); }
        catch (IllegalArgumentException e) { throw new InvalidFundQueryException(e.getMessage()); }
    }

    private FundProfile loadProfile(FundCode code) {
        var profile = fundRepository.findByCode(code).orElseGet(() -> provider.fetchProfile(code)
                .orElseThrow(() -> new FundNotFoundException(code.value())));
        fundRepository.save(profile);
        cache.putProfile(profile);
        return profile;
    }

    private java.util.List<com.jijing.fund.domain.model.NavPoint> loadHistory(FundCode code, LocalDate start, LocalDate end) {
        var points = navRepository.findHistory(code, start, end);
        if (points.isEmpty()) {
            loadProfile(code);
            points = provider.fetchNavHistory(code, start, end);
            if (!points.isEmpty()) {
                navRepository.upsertBatch(points);
                fundRepository.incrementDataRevision(code, points.getLast().navDate());
            }
        }
        cache.putHistory(code, start, end, points);
        return points;
    }

    private FundProfileResult toResult(FundProfile profile) {
        long ageHours = Duration.between(profile.collectedAt(), clock.instant()).toHours();
        String freshness = ageHours <= 48 ? "FRESH" : "STALE";
        return new FundProfileResult(profile.code().value(), profile.name(), profile.fundType(),
                profile.managementCompany(), profile.fundManager(), profile.establishedDate(), profile.dataSource(),
                profile.sourceUpdatedAt(), profile.collectedAt(), freshness);
    }
}
