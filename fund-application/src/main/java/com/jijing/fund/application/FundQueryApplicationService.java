package com.jijing.fund.application;

import com.jijing.fund.application.dto.FundNavHistoryResult;
import com.jijing.fund.application.dto.FundProfileResult;
import com.jijing.fund.application.dto.NavPointResult;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基金档案与净值历史的应用服务。
 * 优先读缓存，缓存缺失或近期区间尾部明显过期时回源落库；无法解释的代码和区间在访问仓储前失败。
 */
public class FundQueryApplicationService implements FundQueryUseCase {
    private final FundRepository fundRepository;
    private final FundNavRepository navRepository;
    private final FundQueryCache cache;
    private final ExternalFundDataProvider provider;
    private final Clock clock;

    /**
     * 装配查询所需的仓储、缓存、外部数据源和时钟。
     * 不校验依赖是否为空；调用时若依赖缺失，由空指针在对应协作点失败。
     */
    public FundQueryApplicationService(FundRepository fundRepository, FundNavRepository navRepository,
            FundQueryCache cache, ExternalFundDataProvider provider, Clock clock) {
        this.fundRepository = fundRepository;
        this.navRepository = navRepository;
        this.cache = cache;
        this.provider = provider;
        this.clock = clock;
    }

    /**
     * 返回基金档案。缓存未命中时从本地或外部数据源装载，并把结果写回缓存。
     * 代码无法构成基金代码时抛出无效查询；两边都没有档案时抛出基金不存在。
     */
    @Override
    public FundProfileResult getProfile(String fundCode) {
        var code = parseCode(fundCode);
        var profile = cache.getProfile(code).orElseGet(() -> loadProfile(code));
        return toResult(profile);
    }

    /**
     * 返回区间净值，并为除首点外的每个样本计算相对前一单位净值的百分比涨跌。
     * 日期缺失或起点晚于终点时抛出无效查询且不访问净值仓储；装载结束后本地仍没有基金档案时抛出基金不存在。
     */
    @Override
    public FundNavHistoryResult getNavHistory(String fundCode, LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new InvalidFundQueryException("startDate and endDate are required and startDate must not be after endDate");
        }
        var code = parseCode(fundCode);
        var points = cache.getHistory(code, startDate, endDate)
                .filter(cached -> !hasStaleRecentTail(cached, endDate))
                .orElseGet(() -> loadHistory(code, startDate, endDate));
        if (fundRepository.findByCode(code).isEmpty()) {
            throw new FundNotFoundException(fundCode);
        }
        var items = new ArrayList<NavPointResult>(points.size());
        for (int i = 0; i < points.size(); i++) {
            var point = points.get(i);
            BigDecimal change = null;
            if (i > 0) {
                BigDecimal previous = points.get(i - 1).unitNav();
                change = point.unitNav()
                        .subtract(previous)
                        .divide(previous, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
            items.add(new NavPointResult(point.navDate(), point.unitNav(), point.accumulatedNav(), change));
        }
        String source = points.isEmpty() ? "database" : points.get(0).dataSource();
        return new FundNavHistoryResult(code.value(), source, items);
    }

    /**
     * 把调用方文本转成领域基金代码。
     * 格式不合法时改抛无效查询，避免把领域构造异常直接泄漏给调用方；文本为 null 时领域构造抛出的空指针不会被这里接住。
     */
    private FundCode parseCode(String fundCode) {
        try {
            return new FundCode(fundCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidFundQueryException(e.getMessage());
        }
    }

    /**
     * 读取或回源基金档案，然后同时写入仓储和缓存。
     * 本地与外部数据源都没有该基金时抛出基金不存在，且不会写入缓存。
     */
    private FundProfile loadProfile(FundCode code) {
        Optional<FundProfile> stored = fundRepository.findByCode(code);
        FundProfile profile;
        if (stored.isPresent()) {
            profile = stored.get();
        } else {
            profile = provider.fetchProfile(code)
                    .orElseThrow(() -> new FundNotFoundException(code.value()));
        }
        fundRepository.save(profile);
        cache.putProfile(profile);
        return profile;
    }

    /**
     * 读取区间净值。本地为空，或终点落在昨天及以后时，先确保档案存在再向外部数据源补数。
     * 补数得到样本后覆盖写入、推进数据版本，并优先返回仓储中的重读结果；外部没有档案时由档案装载抛出基金不存在。
     */
    private List<NavPoint> loadHistory(FundCode code, LocalDate start, LocalDate end) {
        var points = navRepository.findHistory(code, start, end);
        boolean recentWindow = !end.isBefore(LocalDate.now(clock).minusDays(1));
        if (points.isEmpty() || recentWindow) {
            loadProfile(code);
            var fetched = provider.fetchNavHistory(code, start, end);
            if (!fetched.isEmpty()) {
                navRepository.upsertBatch(fetched);
                LocalDate latest = fetched.stream().map(NavPoint::navDate).max(LocalDate::compareTo).orElseThrow();
                fundRepository.incrementDataRevision(code, latest);
                var reloaded = navRepository.findHistory(code, start, end);
                points = reloaded.isEmpty() ? fetched : reloaded;
            }
        }
        cache.putHistory(code, start, end, points);
        return points;
    }

    /**
     * 判断缓存中的近期序列是否尾部过旧，过旧则应放弃缓存并重新装载。
     * 终点早于昨天时视为历史区间，直接相信缓存；样本为空视为过旧；最新净值早于三天前也视为过旧。不抛出业务异常。
     */
    private boolean hasStaleRecentTail(List<NavPoint> points, LocalDate end) {
        LocalDate today = LocalDate.now(clock);
        if (end.isBefore(today.minusDays(1))) {
            return false;
        }
        if (points.isEmpty()) {
            return true;
        }
        LocalDate latest = points.stream().map(NavPoint::navDate).max(LocalDate::compareTo).orElse(LocalDate.MIN);
        return latest.isBefore(today.minusDays(3));
    }

    /**
     * 把领域档案映射为查询结果，并按采集时刻计算新鲜度。
     * 采集时刻距现在不超过四十八小时记为新鲜，否则记为陈旧；采集时刻缺失时由时间运算失败。
     */
    private FundProfileResult toResult(FundProfile profile) {
        long ageHours = Duration.between(profile.collectedAt(), clock.instant()).toHours();
        String freshness = ageHours <= 48 ? "FRESH" : "STALE";
        return new FundProfileResult(profile.code().value(), profile.name(), profile.fundType(),
                profile.managementCompany(), profile.fundManager(), profile.establishedDate(), profile.dataSource(),
                profile.sourceUpdatedAt(), profile.collectedAt(), freshness);
    }
}
