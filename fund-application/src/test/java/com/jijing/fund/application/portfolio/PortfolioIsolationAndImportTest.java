package com.jijing.fund.application.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.jijing.fund.analytics.portfolio.MoneyWeightedReturnCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioConcentrationCalculator;
import com.jijing.fund.analytics.portfolio.PortfolioPositionProjector;
import com.jijing.fund.analytics.portfolio.TimeWeightedReturnCalculator;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserId;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.domain.portfolio.PortfolioId;
import com.jijing.fund.domain.portfolio.PortfolioRepository;
import com.jijing.fund.domain.portfolio.PortfolioStatus;
import com.jijing.fund.domain.portfolio.UserPortfolio;
import com.jijing.fund.domain.repository.FundNavRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 组合在用户之间不可见，以及导入预览会留下非法基金代码这一行错误。
 */
class PortfolioIsolationAndImportTest {
    private final UserId ownerId = new UserId("00000000-0000-0000-0000-000000000001");
    private final UserId otherId = new UserId("00000000-0000-0000-0000-000000000002");
    private final AuthenticatedUser owner = new AuthenticatedUser(ownerId, Set.of(UserRole.USER), "s1");
    private final AuthenticatedUser other = new AuthenticatedUser(otherId, Set.of(UserRole.USER), "s2");
    private final PortfolioId portfolio = new PortfolioId("00000000-0000-0000-0000-000000000aaa");

    /**
     * 仓库按其他用户查询为空时，持仓读取按找不到拒绝。
     */
    @Test
    void userBCannotReadUserAPortfolio() {
        var repository = Mockito.mock(PortfolioRepository.class);
        when(repository.findByIdAndOwner(portfolio, otherId)).thenReturn(Optional.empty());
        var service = service(repository);
        assertThatThrownBy(() -> service.positions(other, portfolio)).isInstanceOf(PortfolioNotFoundException.class);
    }

    /**
     * 同一文件里合法行计入有效，六位以外的基金代码计入无效，预览本身不失败。
     */
    @Test
    void csvPreviewRejectsInvalidFundCodeAndKeepsValidRowCount() {
        var repository = Mockito.mock(PortfolioRepository.class);
        var owned = new UserPortfolio(portfolio, ownerId, "A", "CNY", PortfolioStatus.ACTIVE, 0, Instant.EPOCH, Instant.EPOCH);
        when(repository.findByIdAndOwner(portfolio, ownerId)).thenReturn(Optional.of(owned));
        var csv = "基金代码,交易类型,交易日期,确认日期,确认份额,交易金额,手续费,确认净值\n000001,申购,2026-01-02,2026-01-03,10,100,0,10\nbad,申购,2026-01-02,2026-01-03,10,100,0,10\n"
                .getBytes(StandardCharsets.UTF_8);
        var batch = service(repository).previewImport(owner, portfolio, "trades.csv", csv);
        assertThat(batch.validRows()).isEqualTo(1);
        assertThat(batch.invalidRows()).isEqualTo(1);
        assertThat(batch.rows().get(1).errorCode()).isEqualTo("INVALID_FUND_CODE");
    }

    /**
     * 组装只含给定仓库的用例。净值为空模拟，因为这两个测试不读取净值。
     */
    private PortfolioApplicationService service(PortfolioRepository repository) {
        return new PortfolioApplicationService(repository, Mockito.mock(FundNavRepository.class), new PortfolioPositionProjector(),
                new MoneyWeightedReturnCalculator(), new TimeWeightedReturnCalculator(), new PortfolioConcentrationCalculator(),
                List.of(new CsvSpreadsheetTableReader()), null, Clock.systemUTC());
    }
}
