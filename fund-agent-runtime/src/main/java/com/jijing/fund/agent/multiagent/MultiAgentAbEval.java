package com.jijing.fund.agent.multiagent;

import com.jijing.fund.agent.planning.PlanValidator;
import com.jijing.fund.agent.planning.RuleBasedPlanner;
import com.jijing.fund.agent.routing.ExecutionModeRouter;
import java.util.ArrayList;
import java.util.List;

/**
 * 在固定样本上比较单代理与多代理的质量和成本。实时模型评分不在本次计算内。
 * 样本消息若被路由拒绝，异常会中断整次评估，不会把失败样本记成多代理胜利。
 * 报告类样本质量提升不足或成本过高时，启用策略保持多代理关闭。
 */
public final class MultiAgentAbEval {

    /**
     * 一条离线对照样本。
     * 质量分和额外成本由样本自带，评估过程不重新调用对等代理。路由或计划在样本消息上失败时，该样本不会产出结果。
     */
    public record Case(
            String id,
            String kind,
            String message,
            double singleQuality,
            double multiQuality,
            double extraCost) {
    }

    /**
     * 一条样本的路由与分派观察。
     * 多代理未启动时 {@code multiAgentStarted} 为 false，质量分仍保留样本原值，不把未启动当成执行失败。
     */
    public record CaseResult(
            String id,
            String kind,
            String routedMode,
            boolean multiAgentStarted,
            double singleQuality,
            double multiQuality) {
    }

    /**
     * 一次对照的汇总。
     * 没有报告类样本时平均质量为 0、额外成本记为 1，启用结果为关闭。
     * 普通问答若启动了多代理，计数字段会大于零，调用方据此判定评估失败。
     */
    public record Report(
            double avgSingleQuality,
            double avgMultiQuality,
            double extraCost,
            boolean enableMultiAgent,
            int ordinaryQaMultiStarts,
            List<CaseResult> cases) {
    }

    private final ExecutionModeRouter router = new ExecutionModeRouter();
    private final MultiAgentSupervisor supervisor = new MultiAgentSupervisor(router, new PlanValidator(),
            new RuleBasedPlanner());
    private final MultiAgentEnablementPolicy policy = new MultiAgentEnablementPolicy();

    /**
     * 逐条路由并尝试分派，只把报告类样本计入质量与成本平均。
     * 样本列表为空时返回零质量和单位额外成本，并且不启用多代理。
     * 某条消息缺少执行权限或计划校验失败时，异常冒出，已处理样本不会被写成成功报告。
     */
    public Report evaluate(List<Case> cases) {
        List<CaseResult> results = new ArrayList<>();
        int qaMulti = 0;
        double singleSum = 0;
        double multiSum = 0;
        double costSum = 0;
        int reportCount = 0;
        for (Case sample : cases) {
            var route = router.route(sample.message(), true);
            boolean requested = sample.kind().equals("report");
            var assignment = supervisor.decide(sample.message(), true, requested);
            if ("qa".equals(sample.kind()) && assignment.multiAgent()) {
                qaMulti++;
            }
            results.add(new CaseResult(sample.id(), sample.kind(), route.mode().name(), assignment.multiAgent(),
                    sample.singleQuality(), sample.multiQuality()));
            if ("report".equals(sample.kind())) {
                singleSum += sample.singleQuality();
                multiSum += sample.multiQuality();
                costSum += sample.extraCost();
                reportCount++;
            }
        }
        double averageSingle = reportCount == 0 ? 0 : singleSum / reportCount;
        double averageMulti = reportCount == 0 ? 0 : multiSum / reportCount;
        double cost = reportCount == 0 ? 1 : costSum / reportCount;
        return new Report(averageSingle, averageMulti, cost, policy.enable(averageSingle, averageMulti, cost),
                qaMulti, List.copyOf(results));
    }

    /**
     * 返回内置的四条对照样本，覆盖问答、查询、探索和组合报告。
     * 样本本身不触发审批拒绝；报告样本按当前规则会进入计划执行。
     */
    public static List<Case> defaultDataset() {
        return List.of(
                new Case("qa-drawdown", "qa", "最大回撤是什么意思？", 1.00, 1.00, 1.0),
                new Case("lookup-000001", "lookup", "查询 000001 最新资料", 0.92, 0.90, 1.1),
                new Case("explore-drop", "explore", "000001 最近为什么下跌？", 0.80, 0.82, 1.3),
                new Case("report-3funds", "report", "比较 000001 110022 161725 并结合我的组合生成报告", 0.80, 0.81, 1.2));
    }
}
