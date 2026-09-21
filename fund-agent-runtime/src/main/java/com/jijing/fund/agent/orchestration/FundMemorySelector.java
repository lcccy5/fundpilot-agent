package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Selects relevant per-fund memory files and assembles one compact card per fund. */
final class FundMemorySelector {
    private final ObjectMapper mapper;
    private final FundMemoryProjector projector;

    FundMemorySelector(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.projector = new FundMemoryProjector(mapper);
    }

    Selection select(String question, AgentConversationState state, List<AgentFactCard> candidates,
                     int maxFunds, int tokenBudget) {
        if (candidates == null || candidates.isEmpty()) return Selection.empty();
        Set<FundMemoryCategory> categories = categories(question, state);
        if (categories.isEmpty()) return Selection.empty();
        Set<String> subjects = subjects(question, state);

        List<AgentFactCard> eligible = candidates.stream()
                .filter(card -> state == null || state.updatedAt() == null || card.expiresAt().isAfter(state.updatedAt()))
                .filter(card -> categories.contains(FundMemoryCategory.fromTool(card.toolName())))
                .filter(card -> subjects.isEmpty() || cardSubjects(card).stream().anyMatch(subjects::contains))
                .filter(card -> periodCompatible(card, state))
                .sorted(Comparator.comparing(AgentFactCard::createdAt).reversed())
                .toList();

        Map<String, Map<FundMemoryCategory, AgentFactCard>> folders = new LinkedHashMap<>();
        for (AgentFactCard card : eligible) {
            List<String> cardSubjects = cardSubjects(card);
            if (cardSubjects.isEmpty()) cardSubjects = List.of("UNKNOWN");
            for (String subject : cardSubjects) {
                if (!subjects.isEmpty() && !subjects.contains(subject)) continue;
                folders.computeIfAbsent(subject, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(FundMemoryCategory.fromTool(card.toolName()), card);
            }
        }

        StringBuilder prompt = new StringBuilder();
        List<String> cardIds = new ArrayList<>();
        List<EvidenceReference> evidence = new ArrayList<>();
        int used = 0;
        int fundCount = 0;
        for (Map.Entry<String, Map<FundMemoryCategory, AgentFactCard>> folder : folders.entrySet()) {
            if (fundCount >= Math.max(1, maxFunds)) break;
            ObjectNode sections = mapper.createObjectNode();
            List<AgentFactCard> sourceCards = new ArrayList<>();
            for (Map.Entry<FundMemoryCategory, AgentFactCard> section : folder.getValue().entrySet()) {
                AgentFactCard card = section.getValue();
                JsonNode data = projector.project(section.getKey(), parse(card.dataJson()), question);
                ObjectNode memoryValue = mapper.createObjectNode();
                memoryValue.put("observedAt", card.createdAt().toString());
                memoryValue.put("validUntil", card.expiresAt().toString());
                memoryValue.set("evidenceIds", mapper.valueToTree(card.evidenceIds()));
                memoryValue.set("value", data);
                sections.set(section.getKey().name().toLowerCase(Locale.ROOT), memoryValue);
                sourceCards.add(section.getValue());
            }
            String line = "FUND_MEMORY fund=" + folder.getKey() + " sections=" + sections + "\n";
            int cost = TokenBudgetChatMemory.estimateTokens(line);
            if (used + cost > tokenBudget) continue;
            prompt.append(line);
            used += cost;
            fundCount++;
            for (AgentFactCard card : sourceCards) {
                cardIds.add(card.cardId());
                evidence.addAll(card.evidence());
            }
        }
        return new Selection(prompt.toString(), List.copyOf(new LinkedHashSet<>(cardIds)),
                List.copyOf(new LinkedHashSet<>(evidence)), fundCount, used);
    }

    String statePrompt(String question,AgentConversationState state) {
        if (state == null || (state.activeFund() == null && state.mentionedFunds().isEmpty()
                && state.periodStart() == null && state.activeTopic() == null)) return "";
        ObjectNode node = mapper.createObjectNode();
        boolean hasExplicitFund=java.util.regex.Pattern.compile("(?<!\\d)\\d{6}(?!\\d)").matcher(question==null?"":question).find();
        boolean hasExplicitPeriod=java.util.regex.Pattern.compile("20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}").matcher(question==null?"":question).results().count()>=2;
        if(!hasExplicitFund){if(state.activeFund()!=null)node.put("activeFund",state.activeFund());node.set("mentionedFunds",mapper.valueToTree(state.mentionedFunds()));}
        if(!hasExplicitPeriod){if(state.periodStart()!=null)node.put("periodStart",state.periodStart().toString());if(state.periodEnd()!=null)node.put("periodEnd",state.periodEnd().toString());}
        if(categories(question,null).isEmpty()&&state.activeTopic()!=null)node.put("activeTopic",state.activeTopic());
        if(node.isEmpty())return "";
        return "\n\nCONVERSATION_NOTE（仅用于解析指代，不是外部事实）=" + node;
    }

    private JsonNode parse(String dataJson) {
        try { return mapper.readTree(dataJson); }
        catch (Exception ignored) { return mapper.getNodeFactory().textNode(dataJson); }
    }

    private Set<String> subjects(String question, AgentConversationState state) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)")
                .matcher(question == null ? "" : question);
        while (matcher.find()) values.add(matcher.group(1));
        if (values.isEmpty() && state != null) {
            if (referencesMany(question)) values.addAll(state.mentionedFunds());
            else if (state.activeFund() != null) values.add(state.activeFund());
        }
        return values;
    }

    private boolean referencesMany(String question) {
        String text = question == null ? "" : question;
        return text.contains("这些") || text.contains("这几只") || text.contains("两只")
                || text.contains("比较") || text.contains("分别");
    }

    private Set<FundMemoryCategory> categories(String question, AgentConversationState state) {
        String text = question == null ? "" : question;
        if (containsAny(text, "全面", "综合", "整体分析")) return EnumSet.of(FundMemoryCategory.PROFILE,
                FundMemoryCategory.METRICS, FundMemoryCategory.HOLDINGS, FundMemoryCategory.MARKET_SIGNALS);
        EnumSet<FundMemoryCategory> values = EnumSet.noneOf(FundMemoryCategory.class);
        if (containsAny(text, "经理", "基金公司", "基金类型", "基本资料")) values.add(FundMemoryCategory.PROFILE);
        if (containsAny(text, "实时", "行情", "价格", "估值")) values.add(FundMemoryCategory.REALTIME);
        if (containsAny(text, "回撤", "收益", "收益率", "波动", "夏普", "指标", "表现")) values.add(FundMemoryCategory.METRICS);
        if (containsAny(text, "净值", "走势")) values.add(FundMemoryCategory.NAV);
        if (containsAny(text, "持仓", "重仓")) values.add(FundMemoryCategory.HOLDINGS);
        if (containsAny(text, "利好", "利空", "催化", "风险", "行业")) values.add(FundMemoryCategory.MARKET_SIGNALS);
        if (containsAny(text, "季报", "年报", "公告", "文档")) values.add(FundMemoryCategory.DOCUMENTS);
        if (values.isEmpty() && state != null && state.activeTopic() != null && referencesContext(text)) {
            try { values.add(FundMemoryCategory.valueOf(state.activeTopic())); }
            catch (IllegalArgumentException ignored) { /* Unknown topics do not broaden retrieval. */ }
        }
        return values;
    }

    private boolean referencesContext(String text) {
        return containsAny(text, "它", "这只", "这些", "这几只", "两只", "刚才", "上面", "前面",
                "同期", "同一", "继续", "再", "那个", "其中", "分别");
    }

    private List<String> cardSubjects(AgentFactCard card) {
        if (card.subjectKey() == null || card.subjectKey().isBlank()) return List.of();
        return java.util.Arrays.stream(card.subjectKey().split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private boolean periodCompatible(AgentFactCard card, AgentConversationState state) {
        FundMemoryCategory category=FundMemoryCategory.fromTool(card.toolName());
        if(category!=FundMemoryCategory.METRICS&&category!=FundMemoryCategory.NAV)return true;
        if(state==null||state.periodStart()==null||state.periodEnd()==null)return true;
        return card.evidence().stream().anyMatch(item->item.actualStartDate()!=null&&item.actualEndDate()!=null
                &&Math.abs(java.time.temporal.ChronoUnit.DAYS.between(state.periodStart(),item.actualStartDate()))<=7
                &&Math.abs(java.time.temporal.ChronoUnit.DAYS.between(state.periodEnd(),item.actualEndDate()))<=7);
    }

    private boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }

    record Selection(String prompt, List<String> cardIds, List<EvidenceReference> evidence,
                     int fundCount, int tokens) {
        static Selection empty() { return new Selection("", List.of(), List.of(), 0, 0); }
    }
}
