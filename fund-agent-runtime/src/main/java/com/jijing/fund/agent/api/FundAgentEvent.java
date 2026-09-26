package com.jijing.fund.agent.api;

import java.time.Instant;

/**
 * 对话流式边界上的一条事件。类型和载荷由生产者约定，本类型不解释载荷。
 * 允许空类型或空运行标识，调用方需要自行判断能否展示。
 */
public record FundAgentEvent(String type, String runId, Object data, Instant occurredAt) {

    /**
     * 用当前时间生成一条事件。
     * 不校验类型和运行标识；时钟取的是系统当前时刻，重复调用不会得到相同时间。
     */
    public static FundAgentEvent of(String type, String runId, Object data) {
        return new FundAgentEvent(type, runId, data, Instant.now());
    }
}
