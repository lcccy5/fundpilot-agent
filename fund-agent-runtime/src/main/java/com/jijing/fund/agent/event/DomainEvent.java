package com.jijing.fund.agent.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 在 Agent 运行时边界间传递 DomainEvent 数据的不可变值对象。 */
public record DomainEvent(String eventId,String eventType,String aggregateType,String aggregateId,String ownerUserId,
                          Instant occurredAt,String schemaVersion,String deduplicationKey,Map<String,Object> payload,List<String> evidenceIds,String correlationId) {}
