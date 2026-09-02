package com.jijing.fund.agent.routing;

/** 在 Agent 运行时边界间传递 RouteFeatures 数据的不可变值对象。 */
public record RouteFeatures(int fundCount,int intentCount,boolean personalDataRequired,boolean documentResearchRequired,
                            boolean freshMarketDataRequired,boolean reportRequested,boolean exportOrNotificationRequested,
                            int estimatedToolCalls,boolean backgroundRequested,double ambiguityScore) {}
