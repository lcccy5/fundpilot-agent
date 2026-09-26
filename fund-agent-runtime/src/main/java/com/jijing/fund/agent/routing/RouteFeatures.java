package com.jijing.fund.agent.routing;

/**
 * 路由使用的请求特征，前半段来自确定性规则，后半段来自可选的语义建议。
 * 语义字段为零或 false 表示顾问没有给出可用建议。
 * 特征本身不执行计划；审批需求只被标记，真正的拒绝发生在审批校验。对等代理失败不回写这些计数。
 */
public record RouteFeatures(
        int fundCount,
        int goalCount,
        int estimatedToolCalls,
        int estimatedStages,
        boolean personalDataRequired,
        boolean documentResearchRequired,
        boolean freshMarketDataRequired,
        boolean reportRequested,
        boolean sideEffectRequested,
        boolean approvalRequired,
        boolean backgroundRequested,
        boolean adaptiveResearchRequired,
        boolean clarificationRequired,
        int semanticGoalCount,
        int semanticCapabilityCount,
        int semanticEstimatedStages,
        boolean semanticDependencies,
        boolean semanticCrossSourceVerification,
        boolean semanticIterativeResearch) {

    /**
     * 用一份语义建议覆盖语义计数字段，保留原先的确定性特征。
     * 建议已在构造时完成截断。若顾问失败，调用方不应调用本方法，而应继续使用未富化的特征。
     */
    public RouteFeatures withSemanticAdvice(RouteAdvice advice) {
        return new RouteFeatures(fundCount, goalCount, estimatedToolCalls, estimatedStages, personalDataRequired,
                documentResearchRequired, freshMarketDataRequired, reportRequested, sideEffectRequested,
                approvalRequired, backgroundRequested, adaptiveResearchRequired, clarificationRequired,
                advice.goals().size(), advice.requiredCapabilities().size(), advice.estimatedStages(),
                advice.hasDependencies(), advice.crossSourceVerificationRequired(),
                advice.iterativeResearchRequired());
    }
}
