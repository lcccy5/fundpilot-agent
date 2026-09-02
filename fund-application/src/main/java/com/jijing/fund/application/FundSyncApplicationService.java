package com.jijing.fund.application;

import com.jijing.fund.application.dto.*;
import com.jijing.fund.application.exception.*;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.event.DomainEventPublisher;
import com.jijing.fund.domain.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

public class FundSyncApplicationService implements FundSyncUseCase {
    private final ExternalFundDataProvider provider;
    private final FundRepository fundRepository;
    private final FundNavRepository navRepository;
    private final FundSyncAuditRepository auditRepository;
    private final FundQueryCache cache;
    private final FundSyncLock lock;
    private final Clock clock;
    private final DomainEventPublisher events;

    public FundSyncApplicationService(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock) {
        this(provider, fundRepository, navRepository, auditRepository, cache, lock, clock, DomainEventPublisher.NOOP);
    }

    public FundSyncApplicationService(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock, DomainEventPublisher events) {
        this.provider = provider; this.fundRepository = fundRepository; this.navRepository = navRepository;
        this.auditRepository = auditRepository; this.cache = cache; this.lock = lock; this.clock = clock;
        this.events = events==null?DomainEventPublisher.NOOP:events;
    }

    @Override
    @Transactional
    public FundSyncResult syncFund(String fundCode, LocalDate startDate, LocalDate endDate) {
        FundCode code = parse(fundCode);
        if (startDate == null || endDate == null || startDate.isAfter(endDate))
            throw new InvalidFundQueryException("Invalid sync date range");
        if (!lock.tryLock(code)) throw new SyncAlreadyRunningException(code.value());
        long auditId = auditRepository.start(code, provider.sourceName(), startDate, endDate);
        try {
            FundProfile profile = provider.fetchProfile(code).orElseThrow(() -> new FundNotFoundException(code.value()));
            List<NavPoint> points = validate(code, startDate, endDate, provider.fetchNavHistory(code, startDate, endDate));
            fundRepository.save(profile);
            int saved = navRepository.upsertBatch(points);
            if (saved > 0 && !points.isEmpty()) {
                fundRepository.incrementDataRevision(code, points.getLast().navDate());
                events.append("FUND_NAV_UPDATED","fund",code.value(),null,"v1",
                        "nav-"+code.value()+"-"+points.getLast().navDate(),
                        Map.of("navDate",points.getLast().navDate().toString(),"saved",saved));
            }
            auditRepository.success(auditId, points.size(), saved, clock.instant());
            evictAfterCommit(code);
            return new FundSyncResult(code.value(), provider.sourceName(), points.size(), saved, "SUCCESS");
        } catch (RuntimeException ex) {
            String errorCode = ex instanceof ExternalDataSourceException source ? source.errorCode() : "SYNC_FAILED";
            auditRepository.failure(auditId, errorCode, safeMessage(ex), clock.instant());
            throw ex;
        } finally {
            lock.unlock(code);
        }
    }

    private List<NavPoint> validate(FundCode code, LocalDate start, LocalDate end, List<NavPoint> input) {
        var dates = new HashSet<LocalDate>();
        var result = new ArrayList<NavPoint>();
        for (NavPoint point : input) {
            if (!point.fundCode().equals(code) || point.navDate().isBefore(start) || point.navDate().isAfter(end)
                    || !dates.add(point.navDate()))
                throw new ExternalDataSourceException("DATA_QUALITY_ERROR", "Provider returned invalid fund nav data");
            result.add(point);
        }
        result.sort(Comparator.comparing(NavPoint::navDate));
        return List.copyOf(result);
    }

    private FundCode parse(String value) {
        try { return new FundCode(value); }
        catch (IllegalArgumentException ex) { throw new InvalidFundQueryException(ex.getMessage()); }
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        return message.substring(0, Math.min(message.length(), 500));
    }

    private void evictAfterCommit(FundCode code) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { cache.evict(code); return; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cache.evict(code); }
        });
    }
}
