package com.jijing.fund.agent.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判断净值或回撤是否穿越阈值，并套用冷却时间和安静时段。未穿越、冷却中或安静时段都不会记成已发送。
 */
public final class ThresholdNotificationService {
    /**
     * 一次阈值判断。shouldNotify 为 true 时调用方可以投递；heldForQuietHours 为 true 时只占位，不投递。
     * reason 说明是未穿越、冷却、安静时段还是已触发。
     */
    public record Decision(boolean shouldNotify, boolean heldForQuietHours, String reason) {}

    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final Map<String, Double> lastValue = new ConcurrentHashMap<>();

    /**
     * 判断规则是否应从上一次观察值穿越阈值。向下穿越要求 previous 高于阈值且 current 小于等于阈值；向上则相反。
     * 未穿越返回 NO_CROSSING。冷却未结束返回 COOLDOWN。安静时段返回 QUIET_HOURS_HELD，且不更新最近触发时间。
     * 允许触发时记录当前时间和数值并返回 FIRED。ownerRuleKey 为 null 时由映射实现抛出 NullPointerException。
     */
    public Decision evaluate(String ownerRuleKey, double previous, double current, double threshold, boolean crossingMustBeDownward,
            Duration cooldown, Instant now, boolean quietHours) {
        boolean crossed = crossingMustBeDownward ? previous > threshold && current <= threshold : previous < threshold && current >= threshold;
        if (!crossed) {
            return new Decision(false, false, "NO_CROSSING");
        }
        Instant last = lastFired.get(ownerRuleKey);
        if (last != null && now.isBefore(last.plus(cooldown))) {
            return new Decision(false, false, "COOLDOWN");
        }
        if (quietHours) {
            return new Decision(false, true, "QUIET_HOURS_HELD");
        }
        lastFired.put(ownerRuleKey, now);
        lastValue.put(ownerRuleKey, current);
        return new Decision(true, false, "FIRED");
    }
}
