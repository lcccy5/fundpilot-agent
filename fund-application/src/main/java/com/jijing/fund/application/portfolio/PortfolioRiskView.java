package com.jijing.fund.application.portfolio;

import com.jijing.fund.domain.portfolio.PortfolioId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 某个基准日的组合集中度。权重和 HHI 在没有正市值时为空，状态用 concentrationStatus 说明，不能把空权重当成零。
 * coverage 表示估值是否完整。记录本身不校验字段是否互相一致，调用方可以构造出自相矛盾的权重和状态。
 */
public record PortfolioRiskView(PortfolioId portfolioId, LocalDate asOfDate, BigDecimal maxFundWeight, BigDecimal top3Weight,
        BigDecimal hhi, String concentrationStatus, String coverage, List<String> warnings, String algorithmVersion,
        String disclosureDate, String providerId) {
}
