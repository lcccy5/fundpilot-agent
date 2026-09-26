package com.jijing.fund.agent.verification;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 把已通过核对的研究任务写成面向持有人的报告。核对未通过或报告缺失时拒绝写作，避免把未核对内容发给用户。
 */
public final class ReportWriter {
    /**
     * 根据通过的核对报告和任务列表生成 Markdown。report 为 null 或 passed 为 false 时抛出 IllegalStateException。
     * 没有可展示的研究任务时用“基础数据分析”占位；证据编号为空时写明本次没有可追溯证据编号，仍输出报告。
     */
    public String write(VerificationReport report, List<AgentTaskView> tasks) {
        if (report == null || !report.passed()) {
            throw new IllegalStateException("writer requires a passing verification report");
        }
        String completed = tasks.stream()
                .filter(t -> "SUCCEEDED".equals(t.status()) && !"REPORT_VERIFY".equals(t.capabilityType()) && !"REPORT_WRITE".equals(t.capabilityType()))
                .map(this::taskLabel).collect(Collectors.joining("\n- "));
        String evidence = report.evidenceIds().isEmpty() ? "本次任务未返回可追溯证据编号。" : String.join("、", report.evidenceIds());
        return """
                # 基金研究报告

                ## 研究完成情况
                本次研究已完成数据读取、指标计算与核对。以下内容用于帮助你回看本次研究范围；基金历史表现不代表未来收益。

                ## 已完成分析
                - %s

                ## 证据与追溯
                %s

                ## 使用建议
                请结合自己的持仓比例、持有期限和风险承受能力使用这些研究结果；如需比较某两只基金或查看具体指标，可在首页继续输入基金代码查询。
                """.formatted(completed.isBlank() ? "基础数据分析" : completed, evidence);
    }

    /**
     * 把内部能力名换成持有人可读的说法。未知能力退回任务键，不因缺少文案映射而失败。
     */
    private String taskLabel(AgentTaskView task) {
        return switch (task.capabilityType()) {
            case "FUND_METRICS_QUERY" -> "基金指标计算（" + task.taskKey() + "）";
            case "FUND_COMPARE" -> "基金比较";
            case "PORTFOLIO_SNAPSHOT" -> "持仓读取";
            default -> task.taskKey();
        };
    }
}
