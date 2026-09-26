package com.jijing.fund.agent.execution;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次有界执行剩余的工具次数和令牌额度，支持并发扣减。
 * 初始值小于 0 时按 0 处理；额度不足时扣减失败并保持原值。
 */
public final class ExecutionBudget {
    private final AtomicInteger remainingTools;
    private final AtomicInteger remainingTokens;

    /**
     * 用非负的工具次数和令牌上限创建预算。
     * 负数上限会被收成 0，随后任何正数扣减都会失败。
     */
    public ExecutionBudget(int maxTools, int maxTokens) {
        this.remainingTools = new AtomicInteger(Math.max(0, maxTools));
        this.remainingTokens = new AtomicInteger(Math.max(0, maxTokens));
    }

    /**
     * 尝试扣减工具次数。
     * 剩余不足时返回 false 且不改变余额；扣减量本身为负时会增加余额，调用方不应传入负数。
     */
    public boolean consumeTools(int n) {
        while (true) {
            int cur = remainingTools.get();
            if (cur < n) {
                return false;
            }
            if (remainingTools.compareAndSet(cur, cur - n)) {
                return true;
            }
        }
    }

    /**
     * 尝试扣减令牌额度。
     * 剩余不足时返回 false 且不改变余额；扣减量为负时会增加余额。
     */
    public boolean consumeTokens(int n) {
        while (true) {
            int cur = remainingTokens.get();
            if (cur < n) {
                return false;
            }
            if (remainingTokens.compareAndSet(cur, cur - n)) {
                return true;
            }
        }
    }

    /**
     * 返回当前剩余工具次数。
     * 不会失败。
     */
    public int remainingTools() {
        return remainingTools.get();
    }

    /**
     * 返回当前剩余令牌额度。
     * 不会失败。
     */
    public int remainingTokens() {
        return remainingTokens.get();
    }
}
