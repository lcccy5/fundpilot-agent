package com.jijing.fund.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationReadTest {
    @Test void readStateStaysWithTheOwner() {
        var store = new InMemoryNotificationStore();
        var now = Instant.parse("2026-08-27T08:00:00Z");
        store.record("user-a", "drawdown", "fp-1", false, now);
        store.record("user-b", "drawdown", "fp-2", false, now);
        var mine = store.listOwned("user-a").getFirst();
        assertThat(mine.readAt()).isNull();
        assertThat(store.markRead("user-b", mine.notificationId(), now.plusSeconds(5))).isFalse();
        assertThat(store.listOwned("user-a").getFirst().readAt()).isNull();
        assertThat(store.markRead("user-a", mine.notificationId(), now.plusSeconds(9))).isTrue();
        assertThat(store.listOwned("user-a").getFirst().readAt()).isEqualTo(now.plusSeconds(9));
        assertThat(store.listOwned("user-b").getFirst().readAt()).isNull();
    }
}
