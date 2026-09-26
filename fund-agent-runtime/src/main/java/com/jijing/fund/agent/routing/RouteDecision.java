package com.jijing.fund.agent.routing;

/**
 * 一次路由的可审计结果。
 * 模式、命中规则和特征必须同时存在，便于事后区分强制规则与语义建议。
 * 权限不足时不会产生本对象，而是直接拒绝。计划校验失败、审批拒绝或对等代理失败不改写已记录的决策。
 */
public record RouteDecision(
        ExecutionMode mode,
        DirectVariant directVariant,
        String routerVersion,
        RouteFeatures features,
        String matchedRule,
        String modelSuggestion,
        String overrideReason) {
}
