package com.jijing.fund.agent.orchestration;

import com.jijing.fund.agent.api.AgentConversationState;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministically updates the conversation note without summarizing model output. */
final class ConversationStateResolver {
    private static final Pattern FUND_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern ISO_DATE = Pattern.compile("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?");

    AgentConversationState update(AgentConversationState previous, String message, Instant now) {
        AgentConversationState base = previous == null ? AgentConversationState.empty(null) : previous;
        LinkedHashSet<String> funds = new LinkedHashSet<>(base.mentionedFunds());
        List<String> explicitFunds = findFunds(message);
        funds.addAll(explicitFunds);
        while (funds.size() > 8) funds.remove(funds.iterator().next());

        List<LocalDate> dates = findDates(message);
        LocalDate start = dates.size() >= 2 ? dates.get(0) : base.periodStart();
        LocalDate end = dates.size() >= 2 ? dates.get(1) : base.periodEnd();
        String active = explicitFunds.isEmpty() ? base.activeFund() : explicitFunds.get(explicitFunds.size() - 1);
        String topic = topic(message, base.activeTopic());
        return new AgentConversationState(base.conversationId(), active, List.copyOf(funds), start, end, topic, now);
    }

    private List<String> findFunds(String message) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = FUND_CODE.matcher(message == null ? "" : message);
        while (matcher.find()) values.add(matcher.group(1));
        return List.copyOf(values);
    }

    private List<LocalDate> findDates(String message) {
        List<LocalDate> values = new ArrayList<>();
        Matcher matcher = ISO_DATE.matcher(message == null ? "" : message);
        while (matcher.find()) {
            try { values.add(LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)))); }
            catch (RuntimeException ignored) { /* Invalid dates do not mutate the note. */ }
        }
        return values;
    }

    private String topic(String message, String fallback) {
        String text = message == null ? "" : message;
        if (containsAny(text, "经理", "基金公司", "基金类型", "基本资料")) return FundMemoryCategory.PROFILE.name();
        if (containsAny(text, "实时", "行情", "价格", "估值")) return FundMemoryCategory.REALTIME.name();
        if (containsAny(text, "回撤", "收益", "波动", "夏普", "指标")) return FundMemoryCategory.METRICS.name();
        if (text.contains("净值")) return FundMemoryCategory.NAV.name();
        if (containsAny(text, "持仓", "重仓")) return FundMemoryCategory.HOLDINGS.name();
        if (containsAny(text, "利好", "利空", "催化", "风险", "行业")) return FundMemoryCategory.MARKET_SIGNALS.name();
        if (containsAny(text, "季报", "年报", "公告", "文档")) return FundMemoryCategory.DOCUMENTS.name();
        return fallback;
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
