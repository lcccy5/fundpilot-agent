package com.jijing.fund.domain.event;

import java.util.Map;

/** Appends domain events in the same transaction as business writes. */
public interface DomainEventPublisher {
    DomainEventPublisher NOOP=(eventType,aggregateType,aggregateId,ownerUserId,schemaVersion,deduplicationKey,payload)->{};
    void append(String eventType,String aggregateType,String aggregateId,String ownerUserId,String schemaVersion,String deduplicationKey,Map<String,Object> payload);
}
