package com.jijing.fund.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.model.FundCode;
import com.jijing.fund.domain.model.NavPoint;
import com.jijing.fund.domain.model.NavStatus;
import com.jijing.fund.domain.portfolio.FundTransaction;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.domain.portfolio.PortfolioStatus;
import com.jijing.fund.domain.portfolio.TransactionType;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 组合收益的两条主路径：净值齐全时给出资金加权收益，净值缺失时明确未就绪。
 */
class PortfolioApplicationServiceTest {
    private final UserId userId = new UserId("00000000-0000-0000-0000-000000000123");
    private final AuthenticatedUser user = new AuthenticatedUser(userId, Set.of(UserRole.USER), "session-1");
    private final PortfolioId portfolioId = new PortfolioId("00000000-0000-0000-0000-000000000456");
    private final LocalDate date = LocalDate.of(2026, 1, 10);

    /**
     * 用确认流水和期末市值计算资金加权收益。期末市值进入最后一笔现金流，申购金额为负。
     */
    @Test
    void calculatesXirrFromConfirmedCashFlowsAndEndingValuation() {
        var repository = Mockito.mock(PortfolioRepository.class);
        var navs = Mockito.mock(FundNavRepository.class);
        var portfolio = new UserPortfolio(portfolioId, userId, "长期组合", "CNY", PortfolioStatus.ACTIVE, 0, Instant.EPOCH, Instant.EPOCH);
        var subscription = tx("t1", TransactionType.SUBSCRIPTION, date.minusDays(10), new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO);
        when(repository.findByIdAndOwner(portfolioId, userId)).thenReturn(Optional.of(portfolio));
        when(repository.findTransactions(portfolioId, userId)).thenReturn(List.of(subscription));
        when(navs.findHistory(new FundCode("000001"), LocalDate.of(1990, 1, 1), date)).thenReturn(List.of(nav(date, new BigDecimal("11"))));
        var service = new PortfolioApplicationService(repository, navs, new PortfolioPositionProjector(), new MoneyWeightedReturnCalculator(),
                new TimeWeightedReturnCalculator(), new PortfolioConcentrationCalculator(), List.of(new CsvSpreadsheetTableReader()), null,
                Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));

        var result = service.returns(user, portfolioId, date);

        assertThat(result.returnStatus()).isEqualTo("AVAILABLE");
        assertThat(result.moneyWeightedReturn()).isPositive();
        assertThat(result.cashFlows()).hasSize(2);
        assertThat(result.cashFlows().getFirst().amount()).isEqualByComparingTo("-100");
        assertThat(result.cashFlows().getLast().amount()).isEqualByComparingTo("110");
    }

    /**
     * 没有任何净值时收益状态为未就绪，资金加权收益为空，并指出缺失的基金代码。
     */
    @Test
    void marksReturnUnavailableWhenValuationDataIsMissing() {
        var repository = Mockito.mock(PortfolioRepository.class);
        var navs = Mockito.mock(FundNavRepository.class);
        var portfolio = new UserPortfolio(portfolioId, userId, "长期组合", "CNY", PortfolioStatus.ACTIVE, 0, Instant.EPOCH, Instant.EPOCH);
        when(repository.findByIdAndOwner(portfolioId, userId)).thenReturn(Optional.of(portfolio));
        when(repository.findTransactions(portfolioId, userId)).thenReturn(List.of(
                tx("t1", TransactionType.SUBSCRIPTION, date, new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO)));
        when(navs.findHistory(Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(List.of());
        var service = new PortfolioApplicationService(repository, navs, new PortfolioPositionProjector(), new MoneyWeightedReturnCalculator(),
                new TimeWeightedReturnCalculator(), new PortfolioConcentrationCalculator(), List.of(new CsvSpreadsheetTableReader()), null,
                Clock.systemUTC());

        var result = service.returns(user, portfolioId, date);

        assertThat(result.returnStatus()).isEqualTo("DATA_NOT_READY");
        assertThat(result.moneyWeightedReturn()).isNull();
        assertThat(result.warnings()).contains("NAV_UNAVAILABLE:000001");
    }

    /**
     * 组装一笔测试流水。份额或金额为负时由流水值对象拒绝，本方法只传入非负数字。
     */
    private FundTransaction tx(String id, TransactionType type, LocalDate day, BigDecimal shares, BigDecimal gross, BigDecimal fee) {
        return new FundTransaction(id, portfolioId, userId, new FundCode("000001"), type, day, day, shares, gross, fee,
                new BigDecimal("10"), "CNY", "MANUAL", id, null, Instant.EPOCH);
    }

    /**
     * 组装一个已确认净值点。单位净值不是正数时由净值值对象拒绝。
     */
    private NavPoint nav(LocalDate day, BigDecimal unit) {
        return new NavPoint(new FundCode("000001"), day, unit, unit, unit, NavStatus.CONFIRMED, "test", Instant.EPOCH, Instant.EPOCH);
    }
}
