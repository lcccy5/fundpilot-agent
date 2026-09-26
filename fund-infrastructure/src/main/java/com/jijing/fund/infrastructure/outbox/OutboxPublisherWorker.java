package com.jijing.fund.infrastructure.outbox;

import com.jijing.fund.agent.event.DomainEventDispatcher;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每秒领取一条发件箱事件并交给分发器。分发结果要求进死信时写入死信，否则做幂等消费。
 * 分发器抛出的异常不会在这里捕获，事件会停在发布中直到租约过期后再次被领取。
 * 没有可领事件时什么都不做。工人编号固定为 {@code outbox-worker}。
 */
@Component
public class OutboxPublisherWorker {
    private final JdbcTransactionalOutbox outbox;
    private final DomainEventDispatcher dispatcher;

    /** 分发器决定成功、死信或抛错。 */
    public OutboxPublisherWorker(JdbcTransactionalOutbox outbox, DomainEventDispatcher dispatcher) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
    }

    /** 固定延迟 1 秒。一次 tick 最多处理一条。 */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        Instant now = Instant.now();
        outbox.claimPending("outbox-worker", now).ifPresent(event -> {
            var result = dispatcher.dispatch(event, now);
            if (result.deadLetter()) {
                outbox.deadLetter(event.eventId(), result.reason(), now);
            } else {
                outbox.consumeIdempotent("default-dispatcher", event.eventId(), now);
            }
        });
    }
}
