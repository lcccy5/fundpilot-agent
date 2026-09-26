package com.jijing.fund.agent.execution;

import java.util.List;

/**
 * 有界推理循环中一轮动作的观察结果。
 * 状态为 STOP 时循环会结束；证据标识为空表示本轮没有新证据，连续出现会触发提前停止。
 */
public record Observation(
        String status,
        String summary,
        List<String> evidenceIds,
        List<String> warnings,
        List<String> missingFields,
        String freshness,
        List<String> nextAllowedActions) {
}
