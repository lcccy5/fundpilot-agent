package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.AgentConversationState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用确定性规则更新会话笔记，不摘要模型输出。
 * 非法日期被忽略，笔记保持原区间。基金数量超过 8 只时丢掉最早记住的代码。
 * 计划、路由、审批或对等代理失败不会回滚已经写出的笔记。
 */
final class ConversationStateResolver {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern FUND_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?");

    /**
     * 把本轮原文合并进已有笔记。
     * 没有旧笔记时从空状态开始。显式基金代码更新活跃基金；至少两个有效日期覆盖旧区间，
     * 否则尝试相对区间。无法识别新话题时保留旧话题。解析失败的日期不影响其余字段。
     */
    AgentConversationState update(AgentConversationState previous, String message, Instant now) {
        AgentConversationState base = previous == null ? AgentConversationState.empty(null) : previous;
        LinkedHashSet<String> funds = new LinkedHashSet<>(base.mentionedFunds());
        List<String> explicitFunds = findFunds(message);
        funds.addAll(explicitFunds);
        while (funds.size() > 8) {
            funds.remove(funds.iterator().next());
        }

        List<LocalDate> dates = findDates(message);
        DateRange relative = dates.size() >= 2 ? null : relativePeriod(message, now);
        LocalDate start = dates.size() >= 2 ? dates.get(0) : relative != null ? relative.start() : base.periodStart();
        LocalDate end = dates.size() >= 2 ? dates.get(1) : relative != null ? relative.end() : base.periodEnd();
        String active = explicitFunds.isEmpty() ? base.activeFund() : explicitFunds.get(explicitFunds.size() - 1);
        String topic = topic(message, base.activeTopic());
        return new AgentConversationState(base.conversationId(), active, List.copyOf(funds), start, end, topic, now);
    }

    /**
     * 提取原文中的六位基金代码，保持出现顺序并去重。
     * 原文为 null 时返回空列表，不改写已记住的基金。
     */
    private List<String> findFunds(String message) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = FUND_CODE.matcher(message == null ? "" : message);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return List.copyOf(values);
    }

    /**
     * 提取原文中的日期。
     * 无法构成合法日期的片段被跳过，不使整次笔记更新失败。
     */
    private List<LocalDate> findDates(String message) {
        List<LocalDate> values = new ArrayList<>();
        Matcher matcher = ISO_DATE.matcher(message == null ? "" : message);
        while (matcher.find()) {
            try {
                values.add(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))));
            } catch (RuntimeException ignored) {
                // 非法日期不改写笔记。
            }
        }
        return values;
    }

    /**
     * 按关键词选择本轮话题，识别不到时保留上一轮话题。
     * 多种关键词同时出现时采用先匹配到的类别，不把冲突当成检索失败。
     */
    private String topic(String message, String fallback) {
        String text = message == null ? "" : message;
        if (containsAny(text, "经理", "基金公司", "基金类型", "基本资料")) {
            return FundMemoryCategory.PROFILE.name();
        }
        if (containsAny(text, "实时", "行情", "价格", "估值")) {
            return FundMemoryCategory.REALTIME.name();
        }
        if (containsAny(text, "回撤", "收益", "收益率", "波动", "夏普", "指标", "表现")) {
            return FundMemoryCategory.METRICS.name();
        }
        if (containsAny(text, "净值", "走势")) {
            return FundMemoryCategory.NAV.name();
        }
        if (containsAny(text, "持仓", "重仓")) {
            return FundMemoryCategory.HOLDINGS.name();
        }
        if (containsAny(text, "利好", "利空", "催化", "风险", "行业")) {
            return FundMemoryCategory.MARKET_SIGNALS.name();
        }
        if (containsAny(text, "季报", "年报", "公告", "文档")) {
            return FundMemoryCategory.DOCUMENTS.name();
        }
        return fallback;
    }

    /**
     * 判断文本是否包含任一关键词。
     * 都不包含时返回 false。
     */
    private boolean containsAny(String text, String... terms) {
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把“今年”“近一年”等相对说法换成上海时区下的日期区间。
     * 原文没有相对说法时返回 null，调用方继续使用已有区间。
     */
    private DateRange relativePeriod(String message, Instant now) {
        String text = message == null ? "" : message;
        LocalDate end = now.atZone(BUSINESS_ZONE).toLocalDate();
        if (text.contains("今年")) {
            return new DateRange(end.withDayOfYear(1), end);
        }
        if (containsAny(text, "近一年", "过去一年", "最近一年")) {
            return new DateRange(end.minusYears(1), end);
        }
        if (containsAny(text, "近半年", "过去半年", "最近半年")) {
            return new DateRange(end.minusMonths(6), end);
        }
        if (containsAny(text, "近三个月", "近3个月", "过去三个月", "最近三个月")) {
            return new DateRange(end.minusMonths(3), end);
        }
        if (containsAny(text, "近一个月", "近1个月", "过去一个月", "最近一个月")) {
            return new DateRange(end.minusMonths(1), end);
        }
        if (containsAny(text, "近30天", "过去30天", "最近30天")) {
            return new DateRange(end.minusDays(30), end);
        }
        return null;
    }

    /**
     * 一段闭区间的起止日期。
     * 只用于相对时间的临时结果，不会单独持久化。
     */
    private record DateRange(LocalDate start, LocalDate end) {
    }
}
