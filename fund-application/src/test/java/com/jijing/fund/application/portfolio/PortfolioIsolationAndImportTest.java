package com.jijing.fund.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.portfolio.*;
import com.jijing.fund.domain.identity.*;
import com.jijing.fund.domain.portfolio.*;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PortfolioIsolationAndImportTest {
    private final UserId a=new UserId("00000000-0000-0000-0000-000000000001");
    private final UserId b=new UserId("00000000-0000-0000-0000-000000000002");
    private final AuthenticatedUser userA=new AuthenticatedUser(a,Set.of(UserRole.USER),"s1");
    private final AuthenticatedUser userB=new AuthenticatedUser(b,Set.of(UserRole.USER),"s2");
    private final PortfolioId portfolio=new PortfolioId("00000000-0000-0000-0000-000000000aaa");

    @Test void userBCannotReadUserAPortfolio(){
        var repository=Mockito.mock(PortfolioRepository.class);
        when(repository.findByIdAndOwner(portfolio,b)).thenReturn(Optional.empty());
        var service=service(repository);
        assertThatThrownBy(()->service.positions(userB,portfolio)).isInstanceOf(PortfolioNotFoundException.class);
    }

    @Test void csvPreviewRejectsInvalidFundCodeAndKeepsValidRowCount(){
        var repository=Mockito.mock(PortfolioRepository.class);
        var owned=new UserPortfolio(portfolio,a,"A","CNY",PortfolioStatus.ACTIVE,0,Instant.EPOCH,Instant.EPOCH);
        when(repository.findByIdAndOwner(portfolio,a)).thenReturn(Optional.of(owned));
        var csv="基金代码,交易类型,交易日期,确认日期,确认份额,交易金额,手续费,确认净值\n000001,申购,2026-01-02,2026-01-03,10,100,0,10\nbad,申购,2026-01-02,2026-01-03,10,100,0,10\n".getBytes(StandardCharsets.UTF_8);
        var batch=service(repository).previewImport(userA,portfolio,"trades.csv",csv);
        assertThat(batch.validRows()).isEqualTo(1);
        assertThat(batch.invalidRows()).isEqualTo(1);
        assertThat(batch.rows().get(1).errorCode()).isEqualTo("INVALID_FUND_CODE");
    }

    private PortfolioApplicationService service(PortfolioRepository repository){
        return new PortfolioApplicationService(repository,Mockito.mock(FundNavRepository.class),new PortfolioPositionProjector(),new MoneyWeightedReturnCalculator(),new TimeWeightedReturnCalculator(),new PortfolioConcentrationCalculator(),List.of(new CsvSpreadsheetTableReader()),null,Clock.systemUTC());
    }
}
