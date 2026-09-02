package com.jijing.fund.bootstrap;

import com.jijing.fund.application.*;
import com.jijing.fund.analytics.calculator.FundMetricsCalculator;
import com.jijing.fund.analytics.model.CalculationContext;
import com.jijing.fund.analytics.port.*;
import com.jijing.fund.domain.cache.FundQueryCache;
import com.jijing.fund.domain.lock.FundSyncLock;
import com.jijing.fund.domain.provider.ExternalFundDataProvider;
import com.jijing.fund.domain.research.provider.FundDiscoveryProvider;
import com.jijing.fund.domain.research.provider.MarketQuoteProvider;
import com.jijing.fund.domain.identity.UserAccountRepository;
import com.jijing.fund.infrastructure.security.JdbcUserAccountRepository;
import com.jijing.fund.infrastructure.security.JdbcWatchlistRepository;
import com.jijing.fund.domain.watchlist.WatchlistRepository;
import com.jijing.fund.application.watchlist.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.application.portfolio.*;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.infrastructure.security.JdbcPortfolioRepository;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.application.risk.*;
import com.jijing.fund.domain.risk.RiskProfileRepository;
import java.util.List;
import com.jijing.fund.application.research.RealtimeFundQuoteApplicationService;
import com.jijing.fund.application.research.RealtimeFundQuoteUseCase;
import com.jijing.fund.domain.repository.*;
import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class FundApplicationConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean UserAccountRepository userAccountRepository(JdbcTemplate jdbc){return new JdbcUserAccountRepository(jdbc);}
    @Bean WatchlistRepository watchlistRepository(JdbcTemplate jdbc,ObjectMapper mapper){return new JdbcWatchlistRepository(jdbc,mapper);}
    @Bean WatchlistUseCase watchlistUseCase(WatchlistRepository repository,Clock clock){return new WatchlistApplicationService(repository,clock);}
    @Bean PortfolioRepository portfolioRepository(JdbcTemplate jdbc){return new JdbcPortfolioRepository(jdbc);}
    @Bean PortfolioPositionProjector portfolioPositionProjector(){return new PortfolioPositionProjector();}
    @Bean MoneyWeightedReturnCalculator moneyWeightedReturnCalculator(){return new MoneyWeightedReturnCalculator();}
    @Bean TimeWeightedReturnCalculator timeWeightedReturnCalculator(){return new TimeWeightedReturnCalculator();}
    @Bean PortfolioConcentrationCalculator portfolioConcentrationCalculator(){return new PortfolioConcentrationCalculator();}
    @Bean SpreadsheetTableReader csvSpreadsheetTableReader(){return new CsvSpreadsheetTableReader();}
    @Bean SpreadsheetTableReader xlsxSpreadsheetTableReader(){return new com.jijing.fund.infrastructure.portfolio.XlsxSpreadsheetTableReader();}
    @Bean RiskProfileRepository riskProfileRepository(JdbcTemplate jdbc){return new com.jijing.fund.infrastructure.security.JdbcRiskProfileRepository(jdbc);}
    @Bean RiskProfileUseCase riskProfileUseCase(RiskProfileRepository repository,Clock clock){return new RiskProfileApplicationService(repository,clock);}
    @Bean PortfolioUseCase portfolioUseCase(PortfolioRepository repository,FundNavRepository navs,PortfolioPositionProjector projector,MoneyWeightedReturnCalculator returns,TimeWeightedReturnCalculator twr,PortfolioConcentrationCalculator concentration,List<SpreadsheetTableReader> readers,ObjectMapper mapper,Clock clock){return new PortfolioApplicationService(repository,navs,projector,returns,twr,concentration,readers,mapper,clock);}

    @Bean
    FundQueryUseCase fundQueryUseCase(FundRepository fundRepository, FundNavRepository navRepository,
            FundQueryCache cache, ExternalFundDataProvider provider, Clock clock) {
        return new FundQueryApplicationService(fundRepository, navRepository, cache, provider, clock);
    }

    @Bean
    FundSyncUseCase fundSyncUseCase(ExternalFundDataProvider provider, FundRepository fundRepository,
            FundNavRepository navRepository, FundSyncAuditRepository auditRepository, FundQueryCache cache,
            FundSyncLock lock, Clock clock, com.jijing.fund.domain.event.DomainEventPublisher events) {
        return new FundSyncApplicationService(provider, fundRepository, navRepository, auditRepository, cache, lock, clock, events);
    }

    @Bean
    FundBatchSyncUseCase fundBatchSyncUseCase(FundRepository repository, FundSyncUseCase singleSync) {
        return new FundBatchSyncApplicationService(repository, singleSync);
    }

    @Bean
    FundMetricsCalculator fundMetricsCalculator() { return new FundMetricsCalculator(); }

    @Bean
    CalculationContext calculationContext(Clock clock,
            @Value("${fund.analytics.annual-risk-free-rate:0}") BigDecimal annualRiskFreeRate,
            @Value("${fund.analytics.algorithm-version:fund-metrics-v1}") String algorithmVersion) {
        return new CalculationContext(annualRiskFreeRate, 252, 20, 60, 365, algorithmVersion, clock);
    }

    @Bean
    FundMetricsQueryUseCase fundMetricsQueryUseCase(FundRepository funds, FundNavRepository navs,
            TradingCalendarRepository calendar, FundMetricsCache cache, FundMetricsCalculator calculator,
            CalculationContext context, ExternalFundDataProvider provider) {
        return new FundMetricsQueryApplicationService(funds, navs, calendar, cache, calculator, context, provider);
    }

    @Bean
    FundComparisonUseCase fundComparisonUseCase(FundMetricsQueryUseCase metrics) {
        return new FundComparisonApplicationService(metrics);
    }

    @Bean
    FundMetricSnapshotUseCase fundMetricSnapshotUseCase(FundRepository funds, FundMetricsQueryUseCase metrics,
            FundMetricSnapshotRepository snapshots) {
        return new FundMetricSnapshotApplicationService(funds, metrics, snapshots);
    }

    @Bean
    RealtimeFundQuoteUseCase realtimeFundQuoteUseCase(FundDiscoveryProvider discovery, MarketQuoteProvider quotes,
            Clock clock, @Value("${fund.research.freshness.realtime-quote-max-age:10m}") Duration maxQuoteAge) {
        return new RealtimeFundQuoteApplicationService(discovery, quotes, clock, maxQuoteAge);
    }
}
