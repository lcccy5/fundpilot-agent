package com.jijing.fund.agent.routing;

/** 在 Agent 运行时边界间传递 RouteFeatures 数据的不可变值对象。 */
public record RouteFeatures(int fundCount,int goalCount,int estimatedToolCalls,int estimatedStages,
                            boolean personalDataRequired,boolean documentResearchRequired,
                            boolean freshMarketDataRequired,boolean reportRequested,
                            boolean sideEffectRequested,boolean approvalRequired,
                            boolean backgroundRequested,boolean adaptiveResearchRequired,
                            boolean clarificationRequired) {}
