package com.jijing.fund.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationFailureReadabilityGapTest {
    @Test
    void thresholdHoldsQuietHoursAndCooldownWithoutMarkingThemDelivered() {
        var service = new ThresholdNotificationService();
        Instant now = Instant.parse("2026-09-26T05:00:00Z");
        assertThat(service.evaluate("owner|rule", 0.05, 0.04, 0.10, true, Duration.ofHours(6), now, false).reason())
                .isEqualTo("NO_CROSSING");
        assertThat(service.evaluate("owner|up", 0.05, 0.20, 0.10, false, Duration.ofHours(6), now, false).reason())
                .isEqualTo("FIRED");
        var quiet = service.evaluate("owner|quiet", 0.20, 0.05, 0.10, true, Duration.ofHours(6), now, true);
        assertThat(quiet.shouldNotify()).isFalse();
        assertThat(quiet.heldForQuietHours()).isTrue();
        assertThat(quiet.reason()).isEqualTo("QUIET_HOURS_HELD");
        var first = service.evaluate("owner|down", 0.20, 0.05, 0.10, true, Duration.ofHours(6), now, false);
        assertThat(first.reason()).isEqualTo("FIRED");
        var cooled = service.evaluate("owner|down", 0.20, 0.04, 0.10, true, Duration.ofHours(6), now.plusSeconds(60), false);
        assertThat(cooled.reason()).isEqualTo("COOLDOWN");
        assertThat(cooled.shouldNotify()).isFalse();
        assertThatThrownBy(() -> service.evaluate(null, 0.20, 0.05, 0.10, true, Duration.ofHours(6), now, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void dispatcherDedupesAfterQuietHoldAndPropagatesChannelFailure() {
        var store = new InMemoryNotificationStore();
        var channel = new InAppNotificationChannel();
        var dispatcher = new NotificationDispatcher(store, channel);
        Instant now = Instant.parse("2026-09-26T05:10:00Z");
        var quiet = dispatcher.onDrawdown("owner", "drawdown", 0.20, 0.05, 0.10, true, now);
        assertThat(quiet.delivered()).isFalse();
        assertThat(quiet.status()).isEqualTo("SCHEDULED_QUIET");
        assertThat(store.listOwned("owner")).singleElement().extracting(NotificationStore.StoredNotification::status).isEqualTo("SCHEDULED");

        var deduped = dispatcher.onDrawdown("owner", "drawdown", 0.20, 0.04, 0.10, false, now);
        assertThat(deduped.status()).isEqualTo("DEDUPED");
        assertThat(channel.inbox()).isEmpty();

        var noCrossing = dispatcher.onDrawdown("owner", "other", 0.01, 0.02, 0.10, false, now);
        assertThat(noCrossing.status()).isEqualTo("NO_CROSSING");

        var fired = dispatcher.onDrawdown("owner-2", "drawdown", 0.20, 0.05, 0.10, false, now);
        assertThat(fired.status()).isEqualTo("INBOX");
        var cooled = dispatcher.onDrawdown("owner-2", "drawdown", 0.20, 0.03, 0.10, false, now.plusSeconds(30));
        assertThat(cooled.status()).isEqualTo("COOLDOWN");

        NotificationChannel failing = message -> {
            throw new IllegalStateException("channel down");
        };
        var failingDispatcher = new NotificationDispatcher(new InMemoryNotificationStore(), failing);
        assertThatThrownBy(() -> failingDispatcher.onDrawdown("owner-3", "drawdown", 0.20, 0.05, 0.10, false, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("channel down");
    }

    @Test
    void inAppChannelRejectsSensitiveSummariesAndStoreHidesOtherOwners() {
        var channel = new InAppNotificationChannel();
        assertThat(channel.deliver(null).status()).isEqualTo("REJECTED_SENSITIVE");
        assertThat(channel.deliver(new NotificationChannel.NotificationMessage("owner", null, "/portfolios")).status())
                .isEqualTo("REJECTED_SENSITIVE");
        assertThat(channel.deliver(new NotificationChannel.NotificationMessage("owner", "包含持仓明细", "/portfolios")).delivered()).isFalse();
        assertThat(channel.deliver(new NotificationChannel.NotificationMessage("owner", "回撤超过阈值", "/portfolios")).status()).isEqualTo("INBOX");
        assertThat(channel.inbox()).hasSize(1);

        var store = new InMemoryNotificationStore();
        Instant now = Instant.parse("2026-09-26T05:10:00Z");
        assertThat(store.record("owner", "rule", "fp", false, now)).isTrue();
        assertThat(store.record("owner", "rule", "fp", true, now)).isFalse();
        assertThat(store.record("other", "rule", "fp", false, now)).isTrue();
        assertThat(store.listOwned("owner")).singleElement().extracting(NotificationStore.StoredNotification::status).isEqualTo("PENDING");
        assertThat(store.listOwned("missing")).isEmpty();
    }
}
