package com.jijing.fund.agent.routing;

import java.util.List;
import java.util.Objects;

/**
 * 语义预检给出的非权威特征。
 * 只在强制路由规则都未命中后才可被采纳。空目标、依赖不足或阶段过少时不得把请求抬升为计划执行。
 * 顾问调用失败时调用方应丢弃建议并走确定性回退，而不是把残缺建议当成未知路由的升级信号。
 */
public record RouteAdvice(
        List<String> goals,
        List<String> requiredCapabilities,
        boolean hasDependencies,
        boolean crossSourceVerificationRequired,
        boolean iterativeResearchRequired,
        int estimatedStages,
        String rationale) {

    /**
     * 截断并清洗模型返回的语义特征，避免超长字段进入审计记录。
     * 目标或能力为 null 时变成空列表；阶段数夹紧到 1 至 20。清洗不抛出计划或审批异常。
     */
    public RouteAdvice {
        goals = normalize(goals, 8, 120);
        requiredCapabilities = normalize(requiredCapabilities, 12, 64);
        estimatedStages = Math.max(1, Math.min(20, estimatedStages));
        rationale = rationale == null ? "" : rationale.substring(0, Math.min(500, rationale.length()));
    }

    /**
     * 去掉空白项，截断单条长度，并限制条数。
     * 输入为 null 时返回空列表，调用方据此视为没有可用的语义目标或能力。
     */
    private static List<String> normalize(List<String> values, int maxItems, int maxChars) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> value.substring(0, Math.min(maxChars, value.length())))
                .distinct()
                .limit(maxItems)
                .toList();
    }
}
