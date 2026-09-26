package com.jijing.fund.agent.planning;

/**
 * 计划草稿违反目标、任务、预算、身份或依赖约束时的失败。
 * 抛出后调用方必须放弃该计划，不能按已通过的前半段任务继续执行。
 * 它不表示路由失败、审批拒绝或对等代理执行失败。
 */
public class PlanValidationException extends RuntimeException {

    /**
     * 用调用方可记录的原因构造计划校验失败。
     * 原因包括目标为空、任务为空、未知任务类型、越权输入、缺失依赖或存在环。
     */
    public PlanValidationException(String message) {
        super(message);
    }
}
