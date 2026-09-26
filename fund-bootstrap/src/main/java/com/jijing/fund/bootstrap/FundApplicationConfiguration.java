package com.jijing.fund.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.analytics.calculator.FundMetricsCalculator;
import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.analytics.port.FundMetricSnapshotRepository;
import com.jijing.fund.analytics.port.FundMetricsCache;
import com.jijing.fund.application.FundBatchSyncApplicationService;
import com.jijing.fund.application.FundBatchSyncUseCase;
import com.jijing.fund.application.FundComparisonApplicationService;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricSnapshotApplicationService;
import com.jijing.fund.application.FundMetricSnapshotUseCase;
import com.jijing.fund.application.FundMetricsQueryApplicationService;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryApplicationService;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.FundSyncApplicationService;
import com.jijing.fund.application.FundSyncUseCase;
import com.jijing.fund.application.portfolio.CsvSpreadsheetTableReader;
import com.jijing.fund.application.portfolio.PortfolioApplicationService;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.portfolio.SpreadsheetTableReader;
import com.jijing.fund.application.research.RealtimeFundQuoteApplicationService;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.application.risk.RiskProfileApplicationService;
import com.jijing.fund.application.risk.RiskProfileUseCase;
import com.jijing.fund.application.watchlist.WatchlistApplicationService;
import com.jijing.fund.application.watchlist.WatchlistUseCase;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.event.DomainEventPublisher;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.repository.FundNavRepository;
import com.jijing.fund.domain.repository.FundRepository;
import com.jijing.fund.domain.repository.FundSyncAuditRepository;
import com.jijing.fund.domain.repository.TradingCalendarRepository;
import com.jijing.fund.domain.research.provider.FundDiscoveryProvider;
import com.jijing.fund.domain.research.provider.MarketQuoteProvider;
import com.jijing.fund.domain.risk.RiskProfileRepository;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import com.jijing.fund.infrastructure.security.JdbcPortfolioRepository;
import com.jijing.fund.infrastructure.security.JdbcUserAccountRepository;
import com.jijing.fund.infrastructure.security.JdbcWatchlistRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把查询、同步、组合、自选和风险画像的应用服务接到基础设施适配器上。
 * 这里只负责装配；参数不合法、资源不存在和上游失败由各用例抛出，再由接口层映射。
 */
@Configuration
class FundApplicationConfiguration {
    /**
     * 使用 UTC 时钟，避免指标和令牌过期依赖进程时区。
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 用 JDBC 持久化账号、令牌版本和刷新令牌。
     */
    @Bean
    UserAccountRepository userAccountRepository(JdbcTemplate jdbc) {
        return new JdbcUserAccountRepository(jdbc);
    }

    /**
     * 用 JDBC 持久化自选分组，标签等结构化字段交给 JSON 映射。
     */
    @Bean
    WatchlistRepository watchlistRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new JdbcWatchlistRepository(jdbc, mapper);
    }

    /**
     * 装配按当前用户隔离的自选用例。
     */
    @Bean
    WatchlistUseCase watchlistUseCase(WatchlistRepository repository, Clock clock) {
        return new WatchlistApplicationService(repository, clock);
    }

    /**
     * 用 JDBC 持久化组合、交易和导入批次。
     */
    @Bean
    PortfolioRepository portfolioRepository(JdbcTemplate jdbc) {
        return new JdbcPortfolioRepository(jdbc);
    }

    /**
     * 从交易流水投影当前持仓。
     */
    @Bean
    PortfolioPositionProjector portfolioPositionProjector() {
        return new PortfolioPositionProjector();
    }

    /**
     * 计算资金加权收益。
     */
    @Bean
    MoneyWeightedReturnCalculator moneyWeightedReturnCalculator() {
        return new MoneyWeightedReturnCalculator();
    }

    /**
     * 计算时间加权收益。
     */
    @Bean
    TimeWeightedReturnCalculator timeWeightedReturnCalculator() {
        return new TimeWeightedReturnCalculator();
    }

    /**
     * 计算持仓集中度。
     */
    @Bean
    PortfolioConcentrationCalculator portfolioConcentrationCalculator() {
        return new PortfolioConcentrationCalculator();
    }

    /**
     * 读取 CSV 账单。与表格读取器列表中的其它实现一起注入组合用例。
     */
    @Bean
    SpreadsheetTableReader csvSpreadsheetTableReader() {
        return new CsvSpreadsheetTableReader();
    }

    /**
     * 读取 xlsx 账单。文件损坏时由读取器抛出，组合预览再映射为参数错误。
     */
    @Bean
    SpreadsheetTableReader xlsxSpreadsheetTableReader() {
        return new com.jijing.fund.infrastructure.portfolio.XlsxSpreadsheetTableReader();
    }

    /**
     * 用 JDBC 保存风险画像答卷。
     */
    @Bean
    RiskProfileRepository riskProfileRepository(JdbcTemplate jdbc) {
        return new com.jijing.fund.infrastructure.security.JdbcRiskProfileRepository(jdbc);
    }

    /**
     * 装配问卷读取和画像提交。
     */
    @Bean
    RiskProfileUseCase riskProfileUseCase(RiskProfileRepository repository, Clock clock) {
        return new RiskProfileApplicationService(repository, clock);
    }

    /**
     * 装配组合、估值、收益和账单导入。读取器按列表注入，调用方按文件类型选择。
     */
    @Bean
    PortfolioUseCase portfolioUseCase(PortfolioRepository repository, FundNavRepository navs,
            PortfolioPositionProjector projector, MoneyWeightedReturnCalculator returns,
            TimeWeightedReturnCalculator twr, PortfolioConcentrationCalculator concentration,
            List<SpreadsheetTableReader> readers, ObjectMapper mapper, Clock clock) {
        return new PortfolioApplicationService(repository, navs, projector, returns, twr, concentration, readers, mapper, clock);
    }

    /**
     * 装配基金资料和净值查询，包含缓存与外部数据源回源。
     */
    @Bean
    FundQueryUseCase fundQueryUseCase(FundRepository fundRepository, FundNavRepository navRepository,
            FundQueryCache cache, ExternalFundDataProvider provider, Clock clock) {
        return new FundQueryApplicationService(fundRepository, navRepository, cache, provider, clock);
    }

    /**
     * 装配单基金同步，并在完成后发布领域事件。
     */
    @Bean
    FundSyncUseCase fundSyncUseCase(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock, DomainEventPublisher events) {
        return new FundSyncApplicationService(provider, fundRepository, navRepository, auditRepository, cache, lock, clock, events);
    }

    /**
     * 在单基金同步之上提供批量同步。单只失败由单基金用例决定是否中断。
     */
    @Bean
    FundBatchSyncUseCase fundBatchSyncUseCase(FundRepository repository, FundSyncUseCase singleSync) {
        return new FundBatchSyncApplicationService(repository, singleSync);
    }

    /**
     * 创建无状态的指标计算器。
     */
    @Bean
    FundMetricsCalculator fundMetricsCalculator() {
        return new FundMetricsCalculator();
    }

    /**
     * 固定年化无风险利率、交易日数量和算法版本，供指标结果带出版本号。
     */
    @Bean
    CalculationContext calculationContext(Clock clock,
            @Value("${fund.analytics.annual-risk-free-rate:0}") BigDecimal annualRiskFreeRate,
            @Value("${fund.analytics.algorithm-version:fund-metrics-v1}") String algorithmVersion) {
        return new CalculationContext(annualRiskFreeRate, 252, 20, 60, 365, algorithmVersion, clock);
    }

    /**
     * 装配单基金指标查询。净值不足或口径不支持时由用例抛出对应冲突或不可处理异常。
     */
    @Bean
    FundMetricsQueryUseCase fundMetricsQueryUseCase(FundRepository funds, FundNavRepository navs,
            TradingCalendarRepository calendar, FundMetricsCache cache, FundMetricsCalculator calculator,
            CalculationContext context, ExternalFundDataProvider provider) {
        return new FundMetricsQueryApplicationService(funds, navs, calendar, cache, calculator, context, provider);
    }

    /**
     * 用同一套单基金指标做多基金对比，避免对比路径另写一套公式。
     */
    @Bean
    FundComparisonUseCase fundComparisonUseCase(FundMetricsQueryUseCase metrics) {
        return new FundComparisonApplicationService(metrics);
    }

    /**
     * 把算好的指标快照落库，供后续读取而不是每次重算。
     */
    @Bean
    FundMetricSnapshotUseCase fundMetricSnapshotUseCase(FundRepository funds, FundMetricsQueryUseCase metrics,
            FundMetricSnapshotRepository snapshots) {
        return new FundMetricSnapshotApplicationService(funds, metrics, snapshots);
    }

    /**
     * 装配实时行情。超过配置的最大年龄时，由用例标记为不够新鲜而不是静默返回旧价。
     */
    @Bean
    RealtimeFundQuoteUseCase realtimeFundQuoteUseCase(FundDiscoveryProvider discovery, MarketQuoteProvider quotes,
            Clock clock, @Value("${fund.research.freshness.realtime-quote-max-age:10m}") Duration maxQuoteAge) {
        return new RealtimeFundQuoteApplicationService(discovery, quotes, clock, maxQuoteAge);
    }
}
