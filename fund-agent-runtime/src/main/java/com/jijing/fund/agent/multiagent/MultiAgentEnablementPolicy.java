package com.jijing.fund.agent.multiagent;

/**
 * 决定多代理是否值得相对单代理开启。
 * 质量提升不足 0.05，或额外成本倍数大于 1.5 时保持关闭。
 * 计划校验失败、路由未进入计划执行、审批被拒或对等代理失败都不能用来放宽该门槛。
 */
public final class MultiAgentEnablementPolicy {

    /**
     * 比较单代理质量、多代理质量和额外成本倍数。
     * 返回 false 时调用方不得启动多代理。质量差或成本为 NaN 时比较结果为 false，同样保持关闭。
     */
    public boolean enable(double singleAgentQuality, double multiAgentQuality, double extraCostRatio) {
        return multiAgentQuality - singleAgentQuality >= 0.05 && extraCostRatio <= 1.5;
    }
}
