package com.jijing.fund.agent.routing;

/** 在 Agent 运行时边界间传递 RouteFeatures 数据的不可变值对象。 */
public record RouteFeatures(int fundCount,int goalCount,int estimatedToolCalls,int estimatedStages,
                            boolean personalDataRequired,boolean documentResearchRequired,
                            boolean freshMarketDataRequired,boolean reportRequested,
                            boolean sideEffectRequested,boolean approvalRequired,
                            boolean backgroundRequested,boolean adaptiveResearchRequired,
                            boolean clarificationRequired,int semanticGoalCount,
                            int semanticCapabilityCount,int semanticEstimatedStages,
                            boolean semanticDependencies,boolean semanticCrossSourceVerification,
                            boolean semanticIterativeResearch) {
    public RouteFeatures withSemanticAdvice(RouteAdvice advice){
        return new RouteFeatures(fundCount,goalCount,estimatedToolCalls,estimatedStages,personalDataRequired,
                documentResearchRequired,freshMarketDataRequired,reportRequested,sideEffectRequested,
                approvalRequired,backgroundRequested,adaptiveResearchRequired,clarificationRequired,
                advice.goals().size(),advice.requiredCapabilities().size(),advice.estimatedStages(),
                advice.hasDependencies(),advice.crossSourceVerificationRequired(),advice.iterativeResearchRequired());
    }
}
