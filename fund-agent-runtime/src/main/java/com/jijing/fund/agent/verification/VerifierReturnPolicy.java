package com.jijing.fund.agent.verification;

/**
 * 限制核对角色把计划退回重做的次数。超过上限后不允许再次退回，避免核对与执行无限循环。
 */
public final class VerifierReturnPolicy {
    public static final int MAX_RETURNS = 1;

    /**
     * 尚未退回过时允许退回一次。alreadyReturned 大于等于 1 时返回 false，调用方应停止退回而不是再次改写计划。
     */
    public boolean allowReturn(int alreadyReturned) {
        return alreadyReturned < MAX_RETURNS;
    }
}
