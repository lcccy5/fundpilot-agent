package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundSyncResult;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.SyncAlreadyRunningException;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.event.DomainEventPublisher;
import com.jijing.fund.domain.exception.ExternalDataSourceException;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.domain.repository.FundSyncAuditRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 单只基金同步的应用服务。
 * 在锁和审计之内拉取、校验并落库；失败时记录审计、释放锁，并把原始异常继续抛出。
 */
public class FundSyncApplicationService implements FundSyncUseCase {
    private final ExternalFundDataProvider provider;
    private final FundRepository fundRepository;
    private final FundNavRepository navRepository;
    private final FundSyncAuditRepository auditRepository;
    private final FundQueryCache cache;
    private final FundSyncLock lock;
    private final Clock clock;
    private final DomainEventPublisher events;

    /**
     * 使用空事件发布器构造同步服务。
     * 其余依赖的缺失留到同步执行时暴露，构造本身不触发同步。
     */
    public FundSyncApplicationService(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock) {
        this(provider, fundRepository, navRepository, auditRepository, cache, lock, clock, DomainEventPublisher.NOOP);
    }

    /**
     * 装配同步所需的外部数据源、仓储、缓存、锁、时钟和领域事件发布器。
     * 事件发布器为 null 时改用空实现，避免成功路径因为没有发布器而失败。
     */
    public FundSyncApplicationService(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock, DomainEventPublisher events) {
        this.provider = provider;
        this.fundRepository = fundRepository;
        this.navRepository = navRepository;
        this.auditRepository = auditRepository;
        this.cache = cache;
        this.lock = lock;
        this.clock = clock;
        this.events = events == null ? DomainEventPublisher.NOOP : events;
    }

    /**
     * 同步一只基金的档案和区间净值。
     * 代码非法或区间缺失、颠倒时抛出无效查询，且不会加锁；锁已被占用时抛出同步已在进行，不会写审计也不会释放他人的锁。
     * 上游没有档案时抛出基金不存在。运行时失败会先写失败审计再原样抛出，离开时必定解锁。没有活动事务时立即淘汰缓存。
     */
    @Override
    @Transactional
    public FundSyncResult syncFund(String fundCode, LocalDate startDate, LocalDate endDate) {
        FundCode code = parse(fundCode);
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new InvalidFundQueryException("Invalid sync date range");
        }
        if (!lock.tryLock(code)) {
            throw new SyncAlreadyRunningException(code.value());
        }
        long auditId = auditRepository.start(code, provider.sourceName(), startDate, endDate);
        try {
            FundProfile profile = provider.fetchProfile(code)
                    .orElseThrow(() -> new FundNotFoundException(code.value()));
            List<NavPoint> points = validate(code, startDate, endDate, provider.fetchNavHistory(code, startDate, endDate));
            fundRepository.save(profile);
            int saved = navRepository.upsertBatch(points);
            if (saved > 0 && !points.isEmpty()) {
                fundRepository.incrementDataRevision(code, points.getLast().navDate());
                events.append("FUND_NAV_UPDATED", "fund", code.value(), null, "v1",
                        "nav-" + code.value() + "-" + points.getLast().navDate(),
                        Map.of("navDate", points.getLast().navDate().toString(), "saved", saved));
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

    /**
     * 检查上游净值是否属于该基金、落在请求区间内且日期不重复，并按日期排序后返回不可变列表。
     * 基金不符、越界或日期重复时抛出数据质量错误，不返回部分结果。
     */
    private List<NavPoint> validate(FundCode code, LocalDate start, LocalDate end, List<NavPoint> input) {
        var dates = new HashSet<LocalDate>();
        var result = new ArrayList<NavPoint>();
        for (NavPoint point : input) {
            boolean wrongFund = !point.fundCode().equals(code);
            boolean outOfRange = point.navDate().isBefore(start) || point.navDate().isAfter(end);
            if (wrongFund || outOfRange || !dates.add(point.navDate())) {
                throw new ExternalDataSourceException("DATA_QUALITY_ERROR", "Provider returned invalid fund nav data");
            }
            result.add(point);
        }
        result.sort(Comparator.comparing(NavPoint::navDate));
        return List.copyOf(result);
    }

    /**
     * 把调用方文本转成领域基金代码。
     * 格式不合法时改抛无效查询；文本为 null 时领域构造抛出的空指针不会被接住。
     */
    private FundCode parse(String value) {
        try {
            return new FundCode(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidFundQueryException(ex.getMessage());
        }
    }

    /**
     * 截取可写入审计的失败说明，最长五百个字符。
     * 异常没有消息时改用异常类的简单名，避免审计里出现空说明。
     */
    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        return message.substring(0, Math.min(message.length(), 500));
    }

    /**
     * 在事务提交后淘汰该基金的查询缓存；当前没有活动事务时立即淘汰。
     * 注册同步回调失败时异常会进入同步失败路径，缓存可能尚未淘汰。
     */
    private void evictAfterCommit(FundCode code) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            cache.evict(code);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /**
             * 事务成功提交后再丢弃该基金的查询缓存。
             * 事务回滚时不会执行，因此失败的同步不会提前清掉仍有效的缓存。
             */
            @Override
            public void afterCommit() {
                cache.evict(code);
            }
        });
    }
}
