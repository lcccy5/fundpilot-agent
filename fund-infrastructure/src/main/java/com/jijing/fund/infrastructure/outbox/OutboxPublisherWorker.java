package com.jijing.fund.infrastructure.outbox;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxPublisherWorker {
    private final JdbcTransactionalOutbox outbox;
    private final com.jijing.fund.agent.event.DomainEventDispatcher dispatcher;
    public OutboxPublisherWorker(JdbcTransactionalOutbox outbox,com.jijing.fund.agent.event.DomainEventDispatcher dispatcher){
        this.outbox=outbox;this.dispatcher=dispatcher;
    }
    @Scheduled(fixedDelay=1000)
    public void tick(){
        Instant now=Instant.now();
        outbox.claimPending("outbox-worker",now).ifPresent(event->{
            var result=dispatcher.dispatch(event,now);
            if(result.deadLetter())outbox.deadLetter(event.eventId(),result.reason(),now);
            else outbox.consumeIdempotent("default-dispatcher",event.eventId(),now);
        });
    }
}
