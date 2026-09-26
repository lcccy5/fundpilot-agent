package com.jijing.fund.agent.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jijing.fund.agent.notification.InAppNotificationChannel;
import com.jijing.fund.agent.notification.InMemoryNotificationStore;
import com.jijing.fund.agent.notification.NotificationDispatcher;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 补齐外盒业务失败、空事件和分发器空事件这些尚未锁定的失败路径。
 * 空事件发生在业务动作之后，当前实现不会回滚已经执行的业务。
 */
class TransactionalOutboxReadabilityGapTest {

    /**
     * 业务动作失败时事件不得入盒；事件为空时业务已经执行，随后失败。
     * 两种情况最后都不应该留下可发布记录。
     */
    @Test
    void businessFailureAndNullEventDoNotPublish() {
        var outbox = new TransactionalOutbox();
        var event = sample("e1", "k1");
        assertThatThrownBy(() -> outbox.appendWithBusiness(() -> {
            throw new IllegalStateException("business failed");
        }, event)).isInstanceOf(IllegalStateException.class);
        assertThat(outbox.size()).isZero();

        var business = new AtomicInteger();
        assertThatThrownBy(() -> outbox.appendWithBusiness(business::incrementAndGet, null))
                .isInstanceOf(NullPointerException.class);
        assertThat(business.get()).isEqualTo(1);
        assertThat(outbox.size()).isZero();
        assertThat(outbox.claimPending("monitor", Instant.parse("2026-08-27T08:00:00Z"))).isEmpty();
    }

    /**
     * 空事件和未知架构一样成为死信，不触发通知。
     * 分发器不因此抛出异常。
     */
    @Test
    void nullDomainEventIsDeadLettered() {
        var dispatcher = new DomainEventDispatcher(
                new NotificationDispatcher(new InMemoryNotificationStore(), new InAppNotificationChannel()));
        var result = dispatcher.dispatch(null, Instant.parse("2026-08-27T08:00:00Z"));
        assertThat(result.deadLetter()).isTrue();
        assertThat(result.reason()).isEqualTo("UNKNOWN_SCHEMA");
    }

    /**
     * 构造一条最小领域事件，供外盒失败路径使用。
     * 载荷为空映射，不携带所有者。
     */
    private static DomainEvent sample(String eventId, String key) {
        return new DomainEvent(
                eventId,
                "FUND_NAV_UPDATED",
                "fund",
                "000001",
                null,
                Instant.parse("2026-08-27T08:00:00Z"),
                "v1",
                key,
                Map.of(),
                List.of(),
                "c1");
    }
}
