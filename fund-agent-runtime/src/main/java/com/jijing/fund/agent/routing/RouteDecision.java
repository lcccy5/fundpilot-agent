package com.jijing.fund.agent.routing;

/** 在 Agent 运行时边界间传递 RouteDecision 数据的不可变值对象。 */
public record RouteDecision(ExecutionMode mode,DirectVariant directVariant,String routerVersion,RouteFeatures features,
                            String matchedRule,String modelSuggestion,String overrideReason) {}
