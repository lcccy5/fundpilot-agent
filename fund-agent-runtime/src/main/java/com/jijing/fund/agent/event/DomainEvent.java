package com.jijing.fund.agent.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 已发生、待投递的领域事件，含去重键、载荷和关联标识。
 * 不校验架构版本或载荷形状；无法识别的架构由分发器记为死信。
 */
public record DomainEvent(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String ownerUserId,
        Instant occurredAt,
        String schemaVersion,
        String deduplicationKey,
        Map<String, Object> payload,
        List<String> evidenceIds,
        String correlationId) {
}
