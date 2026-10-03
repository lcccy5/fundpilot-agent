package com.jijing.fund.infrastructure.outbox;

import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class OutboxPublisherWorker {
    private final JdbcTransactionalOutbox outbox;
    private final com.jijing.fund.agent.event.DomainEventDispatcher dispatcher;
    private final PythonAgentEventClient python;
    private final String backend;
    public OutboxPublisherWorker(JdbcTransactionalOutbox outbox,com.jijing.fund.agent.event.DomainEventDispatcher dispatcher,
            PythonAgentEventClient python, @Value("${fund.agent.execution-backend:python}") String backend){
        this.outbox=outbox;this.dispatcher=dispatcher;this.python=python;this.backend=backend;
    }
    @Scheduled(fixedDelay=1000)
    public void tick(){
        Instant now=Instant.now();
        outbox.claimPending("outbox-worker",now).ifPresent(event->{
            try {
                if ("java".equalsIgnoreCase(backend)) {
                    var result=dispatcher.dispatch(event,now);
                    if(result.deadLetter())outbox.deadLetter(event.eventId(),result.reason(),now);
                    else outbox.consumeIdempotent("default-dispatcher",event.eventId(),now);
                } else {
                    var result=python.dispatch(event,now);
                    if(result.deadLetter())outbox.deadLetter(event.eventId(),result.reason(),now);
                    else outbox.consumeIdempotent("python-dispatcher",event.eventId(),now);
                }
            } catch (IllegalStateException error) {
                // 远端暂时不可用时等待重试；业务事务已经提交，不回滚用户操作。
                outbox.retryWait(event.eventId(),now);
            }
        });
    }
}
