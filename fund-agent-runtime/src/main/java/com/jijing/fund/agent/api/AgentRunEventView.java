package com.jijing.fund.agent.api;

import java.time.Instant;

/**
 * 某次运行已经落库的一条进度事件，按序号递增供增量拉取。
 * 不校验序号是否连续，也不拒绝空载荷；序号倒退时由查询方过滤。
 */
public record AgentRunEventView(
        String eventId,
        long sequence,
        String eventType,
        String payloadJson,
        Instant createdAt) {
}
