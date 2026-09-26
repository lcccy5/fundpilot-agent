package com.jijing.fund.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按当前问题挑选相关的基金记忆，并为每只基金拼一张紧凑卡片。
 * 没有候选、问题对不上分区，或单只基金超出剩余 token 时跳过该基金，不因此失败整轮回答。
 * 计划、路由、审批或对等代理失败不会放宽这里的分区和区间过滤。
 */
final class FundMemorySelector {
    private static final Pattern FUND_CODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern EXPLICIT_FUND = Pattern.compile("(?<!\\d)\\d{6}(?!\\d)");
    private static final Pattern EXPLICIT_PERIOD = Pattern.compile("20\\d{2}[-/年]\\d{1,2}[-/月]\\d{1,2}");
    private final ObjectMapper mapper;
    private final FundMemoryProjector projector;

    /**
     * 绑定映射器并创建投影器。
     * 映射器为 null 时立即失败，避免后续选择在空指针处中断回答。
     */
    FundMemorySelector(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        this.projector = new FundMemoryProjector(mapper);
    }

    /**
     * 从候选事实卡中选出可放入提示词的基金记忆。
     * 候选为空或问题没有可识别分区时返回空选择。超预算的基金会被跳过而不是截断半行。
     * 区间不兼容的指标或净值卡被排除，避免把错误区间的事实送给模型。
     */
    Selection select(String question, AgentConversationState state, List<AgentFactCard> candidates,
            int maxFunds, int tokenBudget) {
        if (candidates == null || candidates.isEmpty()) {
            return Selection.empty();
        }
        Set<FundMemoryCategory> categories = categories(question, state);
        if (categories.isEmpty()) {
            return Selection.empty();
        }
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
            if (cardSubjects.isEmpty()) {
                cardSubjects = List.of("UNKNOWN");
            }
            for (String subject : cardSubjects) {
                if (!subjects.isEmpty() && !subjects.contains(subject)) {
                    continue;
                }
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
            if (fundCount >= Math.max(1, maxFunds)) {
                break;
            }
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
            if (used + cost > tokenBudget) {
                continue;
            }
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

    /**
     * 生成只用于消解指代的会话笔记片段。
     * 笔记为空、或问题已经写明基金和区间且没有需要补充的话题时返回空字符串。
     * 该片段明确不是外部事实；计划或审批失败不会把笔记升级成证据。
     */
    String statePrompt(String question, AgentConversationState state) {
        if (state == null || (state.activeFund() == null && state.mentionedFunds().isEmpty()
                && state.periodStart() == null && state.activeTopic() == null)) {
            return "";
        }
        ObjectNode node = mapper.createObjectNode();
        boolean hasExplicitFund = EXPLICIT_FUND.matcher(question == null ? "" : question).find();
        boolean hasExplicitPeriod = EXPLICIT_PERIOD.matcher(question == null ? "" : question).results().count() >= 2;
        if (!hasExplicitFund) {
            if (state.activeFund() != null) {
                node.put("activeFund", state.activeFund());
            }
            node.set("mentionedFunds", mapper.valueToTree(state.mentionedFunds()));
        }
        if (!hasExplicitPeriod) {
            if (state.periodStart() != null) {
                node.put("periodStart", state.periodStart().toString());
            }
            if (state.periodEnd() != null) {
                node.put("periodEnd", state.periodEnd().toString());
            }
        }
        if (categories(question, null).isEmpty() && state.activeTopic() != null) {
            node.put("activeTopic", state.activeTopic());
        }
        if (node.isEmpty()) {
            return "";
        }
        return "\n\nCONVERSATION_NOTE（仅用于解析指代，不是外部事实）=" + node;
    }

    /**
     * 解析事实卡中的 JSON。
     * 解析失败时退回原始字符串节点，不让一张坏卡阻断其他记忆。
     */
    private JsonNode parse(String dataJson) {
        try {
            return mapper.readTree(dataJson);
        } catch (Exception ignored) {
            return mapper.getNodeFactory().textNode(dataJson);
        }
    }

    /**
     * 确定本轮要检索的基金主体。
     * 问题里有代码时只用这些代码；否则在指代多只基金时使用笔记中的全部代码，否则使用活跃基金。
     * 都没有时返回空集，表示不按主体过滤。
     */
    private Set<String> subjects(String question, AgentConversationState state) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Matcher matcher = FUND_CODE.matcher(question == null ? "" : question);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        if (values.isEmpty() && state != null) {
            if (referencesMany(question)) {
                values.addAll(state.mentionedFunds());
            } else if (state.activeFund() != null) {
                values.add(state.activeFund());
            }
        }
        return values;
    }

    /**
     * 判断问题是否在指代多只已经提到的基金。
     * 不匹配时返回 false，检索退回到单只活跃基金或不过滤。
     */
    private boolean referencesMany(String question) {
        String text = question == null ? "" : question;
        return text.contains("这些") || text.contains("这几只") || text.contains("两只")
                || text.contains("比较") || text.contains("分别");
    }

    /**
     * 按问题关键词选择要读取的记忆分区。
     * 问题没有关键词时，仅在存在指代且笔记话题能对应到分区时使用该话题。
     * 未知话题被忽略，检索返回空集而不是放开全部分区。
     */
    private Set<FundMemoryCategory> categories(String question, AgentConversationState state) {
        String text = question == null ? "" : question;
        if (containsAny(text, "全面", "综合", "整体分析")) {
            return EnumSet.of(FundMemoryCategory.PROFILE, FundMemoryCategory.METRICS, FundMemoryCategory.HOLDINGS,
                    FundMemoryCategory.MARKET_SIGNALS);
        }
        EnumSet<FundMemoryCategory> values = EnumSet.noneOf(FundMemoryCategory.class);
        if (containsAny(text, "经理", "基金公司", "基金类型", "基本资料")) {
            values.add(FundMemoryCategory.PROFILE);
        }
        if (containsAny(text, "实时", "行情", "价格", "估值")) {
            values.add(FundMemoryCategory.REALTIME);
        }
        if (containsAny(text, "回撤", "收益", "收益率", "波动", "夏普", "指标", "表现")) {
            values.add(FundMemoryCategory.METRICS);
        }
        if (containsAny(text, "净值", "走势")) {
            values.add(FundMemoryCategory.NAV);
        }
        if (containsAny(text, "持仓", "重仓")) {
            values.add(FundMemoryCategory.HOLDINGS);
        }
        if (containsAny(text, "利好", "利空", "催化", "风险", "行业")) {
            values.add(FundMemoryCategory.MARKET_SIGNALS);
        }
        if (containsAny(text, "季报", "年报", "公告", "文档")) {
            values.add(FundMemoryCategory.DOCUMENTS);
        }
        if (values.isEmpty() && state != null && state.activeTopic() != null && referencesContext(text)) {
            try {
                values.add(FundMemoryCategory.valueOf(state.activeTopic()));
            } catch (IllegalArgumentException ignored) {
                // 未知话题不扩大检索范围。
            }
        }
        return values;
    }

    /**
     * 判断问题是否在指代前文。
     * 没有指代时不使用笔记中的旧话题。
     */
    private boolean referencesContext(String text) {
        return containsAny(text, "它", "这只", "这些", "这几只", "两只", "刚才", "上面", "前面",
                "同期", "同一", "继续", "再", "那个", "其中", "分别");
    }

    /**
     * 拆分事实卡主体键中的基金代码。
     * 主体为空时返回空列表，选择器会把该卡归到 UNKNOWN，而不是套用其他基金。
     */
    private List<String> cardSubjects(AgentFactCard card) {
        if (card.subjectKey() == null || card.subjectKey().isBlank()) {
            return List.of();
        }
        return Arrays.stream(card.subjectKey().split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /**
     * 判断指标或净值卡的证据区间是否接近笔记区间。
     * 其他分区、笔记没有区间，或证据起止与笔记相差不超过 7 天时视为兼容。
     * 区间对不上时该卡被排除，不把错误区间的数字送进提示词。
     */
    private boolean periodCompatible(AgentFactCard card, AgentConversationState state) {
        FundMemoryCategory category = FundMemoryCategory.fromTool(card.toolName());
        if (category != FundMemoryCategory.METRICS && category != FundMemoryCategory.NAV) {
            return true;
        }
        if (state == null || state.periodStart() == null || state.periodEnd() == null) {
            return true;
        }
        return card.evidence().stream().anyMatch(item -> item.actualStartDate() != null && item.actualEndDate() != null
                && Math.abs(ChronoUnit.DAYS.between(state.periodStart(), item.actualStartDate())) <= 7
                && Math.abs(ChronoUnit.DAYS.between(state.periodEnd(), item.actualEndDate())) <= 7);
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
     * 一次记忆选择的提示词片段、卡片标识、证据和开销。
     * 空选择表示本轮没有可用记忆，回答必须重新调用工具，而不是把缺失当成已核验证据。
     */
    record Selection(String prompt, List<String> cardIds, List<EvidenceReference> evidence, int fundCount, int tokens) {

        /**
         * 返回没有任何记忆的选择。
         * 调用方应继续走工具，而不是引用空证据。
         */
        static Selection empty() {
            return new Selection("", List.of(), List.of(), 0, 0);
        }
    }
}
