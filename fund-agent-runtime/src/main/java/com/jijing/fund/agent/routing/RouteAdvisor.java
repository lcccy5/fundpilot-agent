package com.jijing.fund.agent.routing;

import java.util.Optional;

/**
 * 提供语义路由建议。安全边界仍由确定性规则决定，顾问不能覆盖权限、审批或副作用。
 * 无法判断或调用失败时应返回空，或由调用方把运行时异常当成空建议；不得因此选择未定义的路由。
 */
@FunctionalInterface
public interface RouteAdvisor {

    /**
     * 根据原文和已提取的确定性特征给出语义建议。
     * 返回空表示建议不可用，路由器应失败开放到有界 ReAct，而不是升级为计划执行。
     * 计划校验失败、审批拒绝或对等代理失败不由本方法处理。
     */
    Optional<RouteAdvice> advise(String message, RouteFeatures deterministicFeatures);
}
