package com.jijing.fund.application;

import com.jijing.fund.analytics.calculator.FundMetricsCalculator;
import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.model.DataCoverage;
import com.jijing.fund.analytics.model.FundMetricCacheKey;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.NavBasis;
import com.jijing.fund.analytics.model.NavObservation;
import com.jijing.fund.analytics.model.NavSeries;
import com.jijing.fund.analytics.port.FundMetricsCache;
import com.jijing.fund.application.exception.FundNotFoundException;
import com.jijing.fund.application.exception.InvalidFundQueryException;
import com.jijing.fund.application.exception.NavDataNotReadyException;
import com.jijing.fund.application.exception.UnsupportedNavBasisException;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.FundProfile;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.domain.repository.TradingCalendarRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 单只基金指标查询的应用服务。
 * 负责补齐档案与确认净值、决定净值口径，并把可计算序列交给计算器；缓存键包含数据版本和算法版本。
 */
public class FundMetricsQueryApplicationService implements FundMetricsQueryUseCase {
    private final FundRepository funds;
    private final FundNavRepository navs;
    private final TradingCalendarRepository calendar;
    private final FundMetricsCache cache;
    private final FundMetricsCalculator calculator;
    private final CalculationContext context;
    private final ExternalFundDataProvider provider;

    /**
     * 装配指标查询所需的仓储、交易日历、缓存、计算器、计算上下文和外部数据源。
     * 构造时不计算指标；依赖为空时留到计算调用暴露。
     */
    public FundMetricsQueryApplicationService(FundRepository funds, FundNavRepository navs,
            TradingCalendarRepository calendar, FundMetricsCache cache, FundMetricsCalculator calculator,
            CalculationContext context, ExternalFundDataProvider provider) {
        this.funds = funds;
        this.navs = navs;
        this.calendar = calendar;
        this.cache = cache;
        this.calculator = calculator;
        this.context = context;
        this.provider = provider;
    }

    /**
     * 计算一只基金在请求区间上的指标。本地没有可用净值时回源并推进数据版本，命中缓存则不再计算。
     * 代码非法或区间缺失、颠倒时抛出无效查询；档案不存在时抛出基金不存在；没有确认或修正净值时抛出净值未就绪；口径无法识别或累计净值不完整时抛出不支持的净值口径。
     */
    @Override
    public FundMetrics calculate(String fundCode, LocalDate start, LocalDate end, String requestedBasis) {
        FundCode code = parseCode(fundCode);
        if (start == null || end == null || start.isAfter(end)) {
            throw new InvalidFundQueryException("Invalid metrics date range");
        }
        if (funds.findByCode(code).isEmpty()) {
            FundProfile profile = provider.fetchProfile(code)
                    .orElseThrow(() -> new FundNotFoundException(code.value()));
            funds.save(profile);
        }
        List<NavPoint> points = confirmedPoints(navs.findHistory(code, start, end));
        if (points.isEmpty()) {
            List<NavPoint> fetched = provider.fetchNavHistory(code, start, end);
            if (!fetched.isEmpty()) {
                navs.upsertBatch(fetched);
                funds.incrementDataRevision(code, fetched.getLast().navDate());
                points = confirmedPoints(fetched);
            }
        }
        if (points.isEmpty()) {
            throw new NavDataNotReadyException(code.value());
        }
        NavBasis basis = resolveBasis(requestedBasis, points);
        long revision = funds.getDataRevision(code);
        String dataVersion = Long.toString(revision);
        FundMetricCacheKey key = new FundMetricCacheKey(code, start, end, basis, context.annualRiskFreeRate(),
                dataVersion, context.algorithmVersion());
        Optional<FundMetrics> cached = cache.get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        List<NavObservation> observations = points.stream()
                .map(point -> new NavObservation(point.navDate(), value(point, basis)))
                .toList();
        int expected = calendar.countTradingDays("CN", observations.getFirst().date(), observations.getLast().date());
        NavSeries series = new NavSeries(code, basis, start, end, observations,
                DataCoverage.of(observations.size(), expected), dataVersion);
        FundMetrics result = calculator.calculate(series, context);
        cache.put(key, result);
        return result;
    }

    /**
     * 只保留确认或修正后的净值，估算净值不参与指标。
     * 输入为空时返回空列表，不抛出业务异常。
     */
    private List<NavPoint> confirmedPoints(List<NavPoint> points) {
        return points.stream()
                .filter(point -> point.navStatus() == NavStatus.CONFIRMED || point.navStatus() == NavStatus.CORRECTED)
                .toList();
    }

    /**
     * 决定本次计算实际使用的净值口径。
     * 未指定口径时，累计净值齐全则用累计净值，否则用单位净值。
     * 名称无法识别，或明确要求累计净值但存在缺失时，抛出不支持的净值口径。
     * 明确要求调整净值但不完整时改为单位净值，让缺少复权净值的上游仍能完成同一次研究计算，实际口径随结果返回。
     */
    private NavBasis resolveBasis(String requested, List<NavPoint> points) {
        if (requested == null || requested.isBlank()) {
            boolean accumulatedComplete = points.stream().allMatch(point -> point.accumulatedNav() != null);
            if (accumulatedComplete) {
                return NavBasis.ACCUMULATED_NAV;
            }
            return NavBasis.UNIT_NAV;
        }
        try {
            NavBasis basis = NavBasis.valueOf(requested.toUpperCase(Locale.ROOT));
            if (basis == NavBasis.ACCUMULATED_NAV && points.stream().anyMatch(point -> point.accumulatedNav() == null)) {
                throw new UnsupportedNavBasisException("Accumulated NAV is incomplete");
            }
            if (basis == NavBasis.ADJUSTED_NAV && points.stream().anyMatch(point -> point.adjustedNav() == null)) {
                return NavBasis.UNIT_NAV;
            }
            return basis;
        } catch (IllegalArgumentException ex) {
            throw new UnsupportedNavBasisException("Unsupported nav basis: " + requested);
        }
    }

    /**
     * 按口径取出对应净值。
     * 口径与序列不匹配导致数值为空时，后续观测构造会失败；这里不另行翻译异常。
     */
    private BigDecimal value(NavPoint point, NavBasis basis) {
        return switch (basis) {
            case UNIT_NAV -> point.unitNav();
            case ACCUMULATED_NAV -> point.accumulatedNav();
            case ADJUSTED_NAV -> point.adjustedNav();
        };
    }

    /**
     * 把调用方文本转成领域基金代码。
     * 格式不合法时改抛无效查询；文本为 null 时领域构造抛出的空指针不会被接住。
     */
    private FundCode parseCode(String code) {
        try {
            return new FundCode(code);
        } catch (IllegalArgumentException ex) {
            throw new InvalidFundQueryException(ex.getMessage());
        }
    }
}
