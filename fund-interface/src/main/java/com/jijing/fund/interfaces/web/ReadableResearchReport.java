package com.jijing.fund.interfaces.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentTaskView;
import com.jijing.fund.analytics.model.FundMetrics;
import com.jijing.fund.analytics.model.MetricStatus;
import com.jijing.fund.analytics.model.MetricValue;
import com.jijing.fund.application.FundComparisonUseCase;
import com.jijing.fund.application.FundMetricsQueryUseCase;
import com.jijing.fund.application.FundQueryUseCase;
import com.jijing.fund.application.dto.FundComparisonResult;
import com.jijing.fund.application.dto.MetricRanking;
import com.jijing.fund.application.portfolio.PortfolioUseCase;
import com.jijing.fund.application.portfolio.PortfolioValuation;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.portfolio.PortfolioId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Turns a finished research plan into numbers a reader can use. */
@Component
public class ReadableResearchReport {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final ObjectMapper mapper;
    private final FundMetricsQueryUseCase metrics;
    private final FundComparisonUseCase comparison;
    private final FundQueryUseCase funds;
    private final PortfolioUseCase portfolios;

    public ReadableResearchReport(ObjectMapper mapper, FundMetricsQueryUseCase metrics, FundComparisonUseCase comparison, FundQueryUseCase funds, PortfolioUseCase portfolios) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.comparison = comparison;
        this.funds = funds;
        this.portfolios = portfolios;
    }

    public String write(AuthenticatedUser actor, AgentPlanView plan) {
        List<AgentTaskView> tasks = plan.tasks();
        StringBuilder report = new StringBuilder();
        report.append("# 月度回顾\n\n");
        String range = range(tasks);
        report.append(range == null ? "下面是这次研究算出的收益、波动、回撤和持仓。" : "统计区间：" + range + "。下面是这个区间的收益、波动、回撤和持仓。");
        report.append("历史表现不代表未来收益。\n\n");
        report.append("## 各基金表现\n");
        int shown = 0;
        for (AgentTaskView task : tasks) {
            if (!"FUND_METRICS_QUERY".equals(task.capabilityType()) || !"SUCCEEDED".equals(task.status())) continue;
            shown++;
            report.append(metricSection(input(task)));
        }
        if (shown == 0) report.append("这次没有算出可用的基金指标。\n");
        AgentTaskView compare = tasks.stream().filter(task -> "FUND_COMPARE".equals(task.capabilityType()) && "SUCCEEDED".equals(task.status())).findFirst().orElse(null);
        if (compare != null) report.append(compareSection(input(compare)));
        if (tasks.stream().anyMatch(task -> "PORTFOLIO_SNAPSHOT".equals(task.capabilityType()) && "SUCCEEDED".equals(task.status())))
            report.append(portfolioSection(actor));
        report.append("\n## 说明\n指标暂无表示这个区间的净值不够计算。这不是买卖建议。\n");
        return report.toString();
    }

    private String metricSection(Map<String, Object> input) {
        String code = text(input.get("fundCode"));
        String name = name(code);
        try {
            FundMetrics result = metrics.calculate(code, LocalDate.parse(text(input.get("startDate"))), LocalDate.parse(text(input.get("endDate"))), text(input.get("navBasis")));
            return """

                    ### %s %s
                    - 区间收益：%s
                    - 年化波动：%s
                    - 最大回撤：%s
                    - 夏普比率：%s
                    - 实际净值：%s 至 %s，共 %d 个观测日
                    """.formatted(code, name, percent(result.cumulativeReturn()), percent(result.annualizedVolatility()), percent(result.maxDrawdown()), number(result.sharpeRatio()), result.actualStartDate(), result.actualEndDate(), result.observationCount());
        } catch (RuntimeException error) {
            return "\n### " + code + " " + name + "\n这个区间没有算出可用指标。\n";
        }
    }

    private String compareSection(Map<String, Object> input) {
        Object raw = input.get("fundCodes");
        if (!(raw instanceof List<?> codes) || codes.size() < 2) return "";
        try {
            FundComparisonResult result = comparison.compare(codes.stream().map(String::valueOf).toList(), LocalDate.parse(text(input.get("startDate"))), LocalDate.parse(text(input.get("endDate"))), text(input.get("navBasis")));
            StringBuilder section = new StringBuilder("\n## 放在一起看\n");
            section.append("- 区间收益更高：").append(leader(result, "cumulativeReturn")).append('\n');
            section.append("- 最大回撤更小：").append(leader(result, "maxDrawdown")).append('\n');
            section.append("- 波动更低：").append(leader(result, "annualizedVolatility")).append('\n');
            return section.toString();
        } catch (RuntimeException error) {
            return "\n## 放在一起看\n这几只基金没有重叠到可以比较的净值区间。\n";
        }
    }

    private String portfolioSection(AuthenticatedUser actor) {
        try {
            var owned = portfolios.list(actor);
            if (owned.isEmpty()) return "\n## 你的组合\n还没有读到持仓。可以先在「我的组合」导入交易。\n";
            var portfolio = owned.getFirst();
            PortfolioId id = portfolio.portfolioId();
            var valuation = portfolios.valuation(actor, id, LocalDate.now());
            var returns = portfolios.returns(actor, id, LocalDate.now());
            StringBuilder section = new StringBuilder("\n## 你的组合\n");
            section.append("- 截至 ").append(valuation.asOfDate()).append("，持仓市值 ").append(money(valuation.totalValue())).append("，成本 ").append(money(valuation.totalCost())).append("，浮动盈亏 ").append(money(valuation.unrealizedProfit())).append('\n');
            if (returns.timeWeightedReturn() != null) section.append("- 时间加权收益：").append(percent(returns.timeWeightedReturn())).append('\n');
            if (valuation.positions() == null || valuation.positions().isEmpty()) {
                section.append("- 当前没有持仓明细。\n");
                return section.toString();
            }
            section.append("- 持仓：\n");
            BigDecimal total = valuation.totalValue() == null ? BigDecimal.ZERO : valuation.totalValue();
            for (PortfolioValuation.PositionValue position : valuation.positions()) {
                String code = position.position().fundCode().value();
                String weight = total.signum() == 0 || position.value() == null ? "占比暂无" : "占比 " + position.value().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP).toPlainString() + "%";
                section.append("  - ").append(code).append(' ').append(name(code)).append("，市值 ").append(money(position.value())).append("，").append(weight).append('\n');
            }
            return section.toString();
        } catch (RuntimeException error) {
            return "\n## 你的组合\n持仓这次没有读出来。\n";
        }
    }

    private String leader(FundComparisonResult result, String metric) {
        List<MetricRanking> ranking = result.rankings().getOrDefault(metric, List.of());
        if (ranking.isEmpty() || ranking.getFirst().fundCode() == null) return "暂无";
        MetricRanking first = ranking.getFirst();
        String value = "sharpeRatio".equals(metric) ? plain(first.value()) : percent(first.value());
        return first.fundCode() + " " + name(first.fundCode()) + "（" + value + "）";
    }

    private String range(List<AgentTaskView> tasks) {
        for (AgentTaskView task : tasks) {
            if (!"FUND_METRICS_QUERY".equals(task.capabilityType())) continue;
            Map<String, Object> input = input(task);
            String start = text(input.get("startDate"));
            String end = text(input.get("endDate"));
            if (!start.isBlank() && !end.isBlank()) return start + " 至 " + end;
        }
        return null;
    }

    private Map<String, Object> input(AgentTaskView task) {
        if (task.inputJson() == null || task.inputJson().isBlank()) return Map.of();
        try { return mapper.readValue(task.inputJson(), MAP); }
        catch (Exception error) { return new LinkedHashMap<>(); }
    }

    private String name(String code) {
        try { return funds.getProfile(code).name(); }
        catch (RuntimeException error) { return ""; }
    }

    private String percent(MetricValue value) {
        if (value == null || value.status() != MetricStatus.AVAILABLE) return "暂无";
        return percent(value.value());
    }

    private String percent(BigDecimal value) {
        if (value == null) return "暂无";
        return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String number(MetricValue value) {
        if (value == null || value.status() != MetricStatus.AVAILABLE) return "暂无";
        return plain(value.value());
    }

    private String plain(BigDecimal value) {
        return value == null ? "暂无" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String money(BigDecimal value) {
        return value == null ? "暂无" : value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " 元";
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value); }
}
