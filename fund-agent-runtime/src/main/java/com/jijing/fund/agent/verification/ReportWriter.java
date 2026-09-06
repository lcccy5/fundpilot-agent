package com.jijing.fund.agent.verification;

import com.jijing.fund.agent.api.AgentTaskView;
import java.util.List;
import java.util.stream.Collectors;

/** 实现 ReportWriter 所代表的 Agent 运行时职责。 */
public final class ReportWriter {
    
    /** 执行该 Agent 运行时组件中的 write 操作。 */
    public String write(VerificationReport report,List<AgentTaskView> tasks){
        if(report==null||!report.passed())throw new IllegalStateException("writer requires a passing verification report");
        String completed=tasks.stream()
                .filter(t->"SUCCEEDED".equals(t.status())&& !"REPORT_VERIFY".equals(t.capabilityType())&& !"REPORT_WRITE".equals(t.capabilityType()))
                .map(this::taskLabel).collect(Collectors.joining("\n- "));
        String evidence=report.evidenceIds().isEmpty()?"本次任务未返回可追溯证据编号。":String.join("、",report.evidenceIds());
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
                """.formatted(completed.isBlank()?"基础数据分析":completed,evidence);
    }

    /** Maps internal capability names to the language used in the owner-facing report. */
    private String taskLabel(AgentTaskView task){
        return switch(task.capabilityType()){
            case "FUND_METRICS_QUERY" -> "基金指标计算（"+task.taskKey()+"）";
            case "FUND_COMPARE" -> "基金比较";
            case "PORTFOLIO_SNAPSHOT" -> "持仓读取";
            default -> task.taskKey();
        };
    }
}
