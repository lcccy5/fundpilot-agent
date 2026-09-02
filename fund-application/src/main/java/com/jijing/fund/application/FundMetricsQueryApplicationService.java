package com.jijing.fund.application;

import com.jijing.fund.analytics.calculator.FundMetricsCalculator;
import com.jijing.fund.analytics.model.*;
import com.jijing.fund.analytics.port.FundMetricsCache;
import com.jijing.fund.application.exception.*;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public class FundMetricsQueryApplicationService implements FundMetricsQueryUseCase {
    private final FundRepository funds; private final FundNavRepository navs; private final TradingCalendarRepository calendar;
    private final FundMetricsCache cache; private final FundMetricsCalculator calculator; private final CalculationContext context; private final ExternalFundDataProvider provider;
    public FundMetricsQueryApplicationService(FundRepository funds, FundNavRepository navs, TradingCalendarRepository calendar,
            FundMetricsCache cache, FundMetricsCalculator calculator, CalculationContext context, ExternalFundDataProvider provider) {
        this.funds=funds; this.navs=navs; this.calendar=calendar; this.cache=cache; this.calculator=calculator; this.context=context; this.provider=provider;
    }
    @Override public FundMetrics calculate(String fundCode, LocalDate start, LocalDate end, String requestedBasis) {
        FundCode code = parseCode(fundCode);
        if (start == null || end == null || start.isAfter(end)) throw new InvalidFundQueryException("Invalid metrics date range");
        if (funds.findByCode(code).isEmpty()) funds.save(provider.fetchProfile(code).orElseThrow(() -> new FundNotFoundException(code.value())));
        List<NavPoint> points = navs.findHistory(code, start, end).stream()
                .filter(p -> p.navStatus() == NavStatus.CONFIRMED || p.navStatus() == NavStatus.CORRECTED).toList();
        if (points.isEmpty()) {
            List<NavPoint> fetched = provider.fetchNavHistory(code, start, end);
            if (!fetched.isEmpty()) {
                navs.upsertBatch(fetched);
                funds.incrementDataRevision(code, fetched.getLast().navDate());
                points = fetched.stream().filter(p -> p.navStatus() == NavStatus.CONFIRMED || p.navStatus() == NavStatus.CORRECTED).toList();
            }
        }
        if (points.isEmpty()) throw new NavDataNotReadyException(code.value());
        NavBasis basis = resolveBasis(requestedBasis, points);
        long revision = funds.getDataRevision(code); String dataVersion = Long.toString(revision);
        FundMetricCacheKey key = new FundMetricCacheKey(code,start,end,basis,context.annualRiskFreeRate(),dataVersion,context.algorithmVersion());
        Optional<FundMetrics> cached = cache.get(key); if (cached.isPresent()) return cached.get();
        List<NavObservation> observations = points.stream().map(p -> new NavObservation(p.navDate(), value(p,basis))).toList();
        int expected = calendar.countTradingDays("CN", observations.getFirst().date(), observations.getLast().date());
        NavSeries series = new NavSeries(code,basis,start,end,observations,DataCoverage.of(observations.size(),expected),dataVersion);
        FundMetrics result = calculator.calculate(series,context); cache.put(key,result); return result;
    }
    private NavBasis resolveBasis(String requested, List<NavPoint> points) {
        if (requested == null || requested.isBlank()) return points.stream().allMatch(p -> p.accumulatedNav()!=null) ? NavBasis.ACCUMULATED_NAV : NavBasis.UNIT_NAV;
        try {
            NavBasis basis = NavBasis.valueOf(requested.toUpperCase(Locale.ROOT));
            if (basis == NavBasis.ACCUMULATED_NAV && points.stream().anyMatch(p -> p.accumulatedNav()==null)) throw new UnsupportedNavBasisException("Accumulated NAV is incomplete");
            if (basis == NavBasis.ADJUSTED_NAV && points.stream().anyMatch(p -> p.adjustedNav()==null)) throw new UnsupportedNavBasisException("Adjusted NAV is unavailable");
            return basis;
        } catch (IllegalArgumentException ex) { throw new UnsupportedNavBasisException("Unsupported nav basis: " + requested); }
    }
    private BigDecimal value(NavPoint p, NavBasis b) { return switch(b){case UNIT_NAV->p.unitNav();case ACCUMULATED_NAV->p.accumulatedNav();case ADJUSTED_NAV->p.adjustedNav();}; }
    private FundCode parseCode(String code) { try{return new FundCode(code);}catch(IllegalArgumentException ex){throw new InvalidFundQueryException(ex.getMessage());} }
}
