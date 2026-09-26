package com.jijing.fund.application.portfolio;

import com.jijing.fund.domain.portfolio.FundPosition;
import com.jijing.fund.domain.portfolio.PortfolioId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 组合在基准日的成本、市值和未实现盈亏。任一持仓缺少净值时，合计市值和浮盈必须为空，并由 warnings 指出缺口。
 * 记录不强制合计与明细相等，错误的调用方可以构造不一致的数字。
 */
public record PortfolioValuation(PortfolioId portfolioId, LocalDate asOfDate, BigDecimal totalCost, BigDecimal totalValue,
        BigDecimal unrealizedProfit, List<PositionValue> positions, List<String> warnings, String algorithmVersion) {
    /**
     * 单只基金的估值。nav、navDate 和 value 在没有可用净值时为空，coverageStatus 用文本标明 AVAILABLE、STALE_NAV 或 UNAVAILABLE。
     * 不校验份额与市值是否匹配。
     */
    public record PositionValue(FundPosition position, BigDecimal nav, LocalDate navDate, BigDecimal value, String coverageStatus) {
    }
}
