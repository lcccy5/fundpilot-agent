package com.jijing.fund.agent.port;

import java.time.Instant;
import java.util.List;

/**
 * 一次工具调用的审计记录，包含参数摘要、结果状态和证据标识。
 * 错误码为空表示调用没有失败；本类型不校验起止时间的先后。
 */
public record AgentToolCallRecord(
        String runId,
        String toolName,
        String toolVersion,
        String argumentHash,
        String argumentsRedactedJson,
        String resultStatus,
        List<String> evidenceIds,
        String errorCode,
        long durationMs,
        Instant startedAt,
        Instant completedAt) {
}
