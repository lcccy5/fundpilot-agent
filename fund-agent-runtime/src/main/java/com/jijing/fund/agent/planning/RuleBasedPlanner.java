package com.jijing.fund.agent.planning;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 用确定性规则把用户原文展开成可比对、可校验的计划草稿。
 * 催化和下跌归因各自收成一个由 LangGraph4j 持有的外部任务；其余请求展开为指标、对比、可选组合、核验和撰写。
 * 草稿仍须通过 {@link PlanValidator}。规则无法识别基金代码时填入默认代码，而不是交出空计划；
 * 路由失败、审批拒绝或对等代理失败不会让本规划器改写任务类型。
 */
public final class RuleBasedPlanner {
    private static final Pattern FUND = Pattern.compile("\\d{6}");
    /** 各本地数据源都能提供单位净值，同一份计划内部因此保持同一种净值口径。 */
    private static final String DEFAULT_NAV_BASIS = "UNIT_NAV";
    private final Clock clock;

    /**
     * 使用 UTC 系统时钟创建规划器。
     * 时钟不可用不是这里处理的失败；区间计算依赖后续显式传入的时钟。
     */
    public RuleBasedPlanner() {
        this(Clock.systemUTC());
    }

    /**
     * 使用指定时钟确定指标区间的结束日。
     * 时钟为 null 时立即失败，避免计划在缺少时间基准时静默使用不确定的日期。
     */
    public RuleBasedPlanner(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * 按原文关键词生成一份计划草稿。
     * 原文为 null 时按空字符串处理。先匹配催化，再匹配归因或下跌原因；两者都不是时，
     * 缺少六位基金代码则使用 000001、110022、161725，保证任务列表不为空。
     * 含导出、发布或通知时追加必须审批的导出任务。本方法不校验草稿，校验失败由调用方拒绝执行。
     */
    public PlanDraft draft(String message) {
        String text = message == null ? "" : message;
        List<String> funds = FUND.matcher(text).results().map(match -> match.group()).distinct().toList();
        if (text.contains("催化")) {
            return catalystDraft(text, funds);
        }
        if (text.contains("归因") || text.contains("下跌原因") || (text.contains("为什么") && text.contains("下跌"))) {
            return declineAttributionDraft(text, funds);
        }
        if (funds.isEmpty()) {
            funds = List.of("000001", "110022", "161725");
        }
        boolean personal = text.contains("组合") || text.contains("我的");
        boolean export = text.contains("导出") || text.contains("发布") || text.contains("通知");
        LocalDate endDate = LocalDate.now(clock);
        LocalDate startDate = endDate.minusYears(1);
        List<PlanTaskDraft> tasks = new ArrayList<>();
        List<String> metricKeys = new ArrayList<>();
        for (String fund : funds) {
            String key = "metrics-" + fund;
            metricKeys.add(key);
            tasks.add(new PlanTaskDraft(key, "FUND_METRICS_QUERY", Map.of(
                    "fundCode", fund,
                    "startDate", startDate.toString(),
                    "endDate", endDate.toString(),
                    "navBasis", DEFAULT_NAV_BASIS), List.of(), List.of("FUND_METRICS")));
        }
        tasks.add(new PlanTaskDraft("compare", "FUND_COMPARE", Map.of(
                "fundCodes", funds,
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "navBasis", DEFAULT_NAV_BASIS), List.copyOf(metricKeys), List.of("FUND_COMPARE")));
        List<String> beforeVerify = new ArrayList<>(List.of("compare"));
        if (personal) {
            tasks.add(new PlanTaskDraft("portfolio", "PORTFOLIO_SNAPSHOT", Map.of("scope", "default"),
                    List.of("compare"), List.of("PORTFOLIO_SNAPSHOT")));
            beforeVerify.add("portfolio");
        }
        tasks.add(new PlanTaskDraft("verify", "REPORT_VERIFY", Map.of(), List.copyOf(beforeVerify),
                List.of("VERIFICATION")));
        tasks.add(new PlanTaskDraft("write", "REPORT_WRITE", Map.of(), List.of("verify"), List.of("REPORT")));
        if (export) {
            tasks.add(new PlanTaskDraft("export", "REPORT_EXPORT", Map.of("format", "markdown"),
                    List.of("write"), List.of("EXPORT")));
        }
        return new PlanDraft(text, Map.of(
                "fundCodes", funds,
                "startDate", startDate.toString(),
                "endDate", endDate.toString(),
                "navBasis", DEFAULT_NAV_BASIS), Map.of("maxTasks", 20, "maxToolCalls", 20), List.copyOf(tasks));
    }

    /**
     * 生成只含一个催化研究任务的计划。
     * 有基金代码时绑定第一只基金，否则把整段原文当作主题。内部可恢复流程由 LangGraph4j 持有，
     * 外层计划失败时不会拆成多个未校验任务。
     */
    private PlanDraft catalystDraft(String text, List<String> funds) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (!funds.isEmpty()) {
            input.put("fundCode", funds.getFirst());
        } else {
            input.put("theme", text);
        }
        input.put("lookbackDays", 45);
        return new PlanDraft(text, Map.of("fundCodes", funds, "researchType", "CATALYST"),
                Map.of("maxTasks", 1, "maxToolCalls", 8),
                List.of(new PlanTaskDraft("catalyst-research", "CATALYST_RESEARCH", Map.copyOf(input),
                        List.of(), List.of("CATALYST_RESEARCH"))));
    }

    /**
     * 生成只含一个下跌归因任务的计划。
     * 有基金代码时绑定第一只基金，否则把整段原文当作主题。内部自适应分支由 LangGraph4j 持有；
     * 外层校验失败时整份归因计划被拒绝。
     */
    private PlanDraft declineAttributionDraft(String text, List<String> funds) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (!funds.isEmpty()) {
            input.put("fundCode", funds.getFirst());
        } else {
            input.put("theme", text);
        }
        input.put("lookbackDays", 45);
        return new PlanDraft(text, Map.of("fundCodes", funds, "researchType", "DECLINE_ATTRIBUTION"),
                Map.of("maxTasks", 1, "maxToolCalls", 8),
                List.of(new PlanTaskDraft("decline-attribution", "DECLINE_ATTRIBUTION", Map.copyOf(input),
                        List.of(), List.of("DECLINE_ATTRIBUTION"))));
    }
}
