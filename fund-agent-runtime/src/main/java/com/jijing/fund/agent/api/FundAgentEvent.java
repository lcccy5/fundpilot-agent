package com.jijing.fund.agent.api;

import java.time.Instant;

/** 在 Agent 运行时边界间传递 FundAgentEvent 数据的不可变值对象。 */
public record FundAgentEvent(String type, String runId, Object data, Instant occurredAt) {
    
    /** 执行该 Agent 运行时组件中的 of 操作。 */
    public static FundAgentEvent of(String type, String runId, Object data) {
        return new FundAgentEvent(type, runId, data, Instant.now());
    }
}
