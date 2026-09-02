package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEvent;
import com.jijing.fund.domain.event.DomainEventPublisher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class JdbcDomainEventPublisher implements DomainEventPublisher {
    private final JdbcTransactionalOutbox outbox;
    public JdbcDomainEventPublisher(JdbcTransactionalOutbox outbox){this.outbox=outbox;}
    @Override public void append(String eventType,String aggregateType,String aggregateId,String ownerUserId,String schemaVersion,String deduplicationKey,Map<String,Object> payload){
        outbox.append(new DomainEvent(UUID.randomUUID().toString(),eventType,aggregateType,aggregateId,ownerUserId,Instant.now(),schemaVersion,deduplicationKey,payload==null?Map.of():payload,List.of(),null));
    }
}
