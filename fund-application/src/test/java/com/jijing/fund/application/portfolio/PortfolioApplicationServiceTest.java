package com.jijing.fund.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.model.*;
import com.jijing.fund.domain.portfolio.*;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PortfolioApplicationServiceTest {
    private final UserId userId=new UserId("00000000-0000-0000-0000-000000000123");
    private final AuthenticatedUser user=new AuthenticatedUser(userId,Set.of(UserRole.USER),"session-1");
    private final PortfolioId portfolioId=new PortfolioId("00000000-0000-0000-0000-000000000456");
    private final LocalDate date=LocalDate.of(2026,1,10);

    @Test void calculatesXirrFromConfirmedCashFlowsAndEndingValuation(){
        var repository=Mockito.mock(PortfolioRepository.class);var navs=Mockito.mock(FundNavRepository.class);
        var portfolio=new UserPortfolio(portfolioId,userId,"长期组合","CNY",PortfolioStatus.ACTIVE,0,Instant.EPOCH,Instant.EPOCH);
        var subscription=tx("t1",TransactionType.SUBSCRIPTION,date.minusDays(10),new BigDecimal("10"),new BigDecimal("100"),BigDecimal.ZERO);
        when(repository.findByIdAndOwner(portfolioId,userId)).thenReturn(Optional.of(portfolio));
        when(repository.findTransactions(portfolioId,userId)).thenReturn(List.of(subscription));
        when(navs.findHistory(new FundCode("000001"),LocalDate.of(1990,1,1),date)).thenReturn(List.of(nav(date,new BigDecimal("11"))));
        var service=new PortfolioApplicationService(repository,navs,new PortfolioPositionProjector(),new MoneyWeightedReturnCalculator(),new TimeWeightedReturnCalculator(),new PortfolioConcentrationCalculator(),List.of(new CsvSpreadsheetTableReader()),null,Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(),ZoneOffset.UTC));

        var result=service.returns(user,portfolioId,date);

        assertThat(result.returnStatus()).isEqualTo("AVAILABLE");
        assertThat(result.moneyWeightedReturn()).isPositive();
        assertThat(result.cashFlows()).hasSize(2);
        assertThat(result.cashFlows().getFirst().amount()).isEqualByComparingTo("-100");
        assertThat(result.cashFlows().getLast().amount()).isEqualByComparingTo("110");
    }

    @Test void marksReturnUnavailableWhenValuationDataIsMissing(){
        var repository=Mockito.mock(PortfolioRepository.class);var navs=Mockito.mock(FundNavRepository.class);
        var portfolio=new UserPortfolio(portfolioId,userId,"长期组合","CNY",PortfolioStatus.ACTIVE,0,Instant.EPOCH,Instant.EPOCH);
        when(repository.findByIdAndOwner(portfolioId,userId)).thenReturn(Optional.of(portfolio));
        when(repository.findTransactions(portfolioId,userId)).thenReturn(List.of(tx("t1",TransactionType.SUBSCRIPTION,date,new BigDecimal("10"),new BigDecimal("100"),BigDecimal.ZERO)));
        when(navs.findHistory(Mockito.any(),Mockito.any(),Mockito.any())).thenReturn(List.of());
        var service=new PortfolioApplicationService(repository,navs,new PortfolioPositionProjector(),new MoneyWeightedReturnCalculator(),new TimeWeightedReturnCalculator(),new PortfolioConcentrationCalculator(),List.of(new CsvSpreadsheetTableReader()),null,Clock.systemUTC());

        var result=service.returns(user,portfolioId,date);

        assertThat(result.returnStatus()).isEqualTo("DATA_NOT_READY");
        assertThat(result.moneyWeightedReturn()).isNull();
        assertThat(result.warnings()).contains("NAV_UNAVAILABLE:000001");
    }

    private FundTransaction tx(String id,TransactionType type,LocalDate day,BigDecimal shares,BigDecimal gross,BigDecimal fee){return new FundTransaction(id,portfolioId,userId,new FundCode("000001"),type,day,day,shares,gross,fee,new BigDecimal("10"),"CNY","MANUAL",id,null,Instant.EPOCH);}
    private NavPoint nav(LocalDate day,BigDecimal unit){return new NavPoint(new FundCode("000001"),day,unit,unit,unit,NavStatus.CONFIRMED,"test",Instant.EPOCH,Instant.EPOCH);}
}
