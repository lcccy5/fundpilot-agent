package com.jijing.fund.agent.planning;

/**
 * 限制同一目标在失败后还能重新规划的次数。
 * 已用次数达到上限时拒绝再规划，调用方应停止生成替代计划。
 * 路由失败、审批拒绝或对等代理失败不会在这里被改写成允许重规划。
 */
public final class ReplanPolicy {
    private final int maxReplans;

    /**
     * 保存非负的重规划上限。
     * 传入负数时按零处理，随后任何重规划请求都会被拒绝。
     */
    public ReplanPolicy(int maxReplans) {
        this.maxReplans = Math.max(0, maxReplans);
    }

    /**
     * 判断已经重规划指定次数后是否还允许再规划一次。
     * 返回 false 表示计划失败后的重试额度已用尽，调用方不得再提交新草稿。
     */
    public boolean allow(int already) {
        return already < maxReplans;
    }
}
