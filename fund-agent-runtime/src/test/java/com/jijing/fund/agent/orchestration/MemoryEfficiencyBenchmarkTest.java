package com.jijing.fund.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Deterministic A/B replay for the production conversation-memory budgets.
 *
 * <p>The baseline sends all prior messages and calls every required tool. The memory variant uses
 * {@link TokenBudgetChatMemory}, injects active fact cards under the production 12-card/3K-token
 * limits, and reuses an exact tool/subject fact instead of calling that tool again. The benchmark
 * measures the memory mechanism itself; it does not claim to measure a live model's willingness to
 * use an injected fact.</p>
 */
class MemoryEfficiencyBenchmarkTest {
    private static final int CONVERSATIONS = 12;
    private static final int TURNS_PER_CONVERSATION = 24;
    private static final int MAX_MESSAGES = 20;
    private static final int MAX_HISTORY_TOKENS = 8_000;
    private static final int MAX_FACT_CARDS = 12;
    private static final int MAX_FACT_TOKENS = 3_000;

    private static BenchmarkReport report;

    @BeforeAll
    static void runBenchmark() throws Exception {
        report = simulate();
        Path target = Path.of("target", "memory-efficiency-report.json");
        Files.createDirectories(target.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        System.out.printf(Locale.ROOT,
                "MEMORY_EVAL conversations=%d turns=%d historyTokenReduction=%.2f%% netMemoryPayloadReduction=%.2f%% factReuseHitRate=%.2f%% totalToolCallReduction=%.2f%% repeatedToolCallReduction=%.2f%%%n",
                report.conversations(), report.turns(), report.historyTokenReductionPercent(),
                report.netMemoryPayloadReductionPercent(), report.factReuseHitRatePercent(),
                report.totalToolCallReductionPercent(), report.repeatedToolCallReductionPercent());
    }

    @Test
    void boundedWindowReducesHistoricalContextTokens() {
        assertThat(report.historyTokenReductionPercent()).isGreaterThan(25.0);
        assertThat(report.boundedHistoryTokens()).isLessThan(report.fullHistoryTokens());
    }

    @Test
    void activeRelevantFactCardsAreReusedUnderTheProductionBudget() {
        assertThat(report.factReuseHitRatePercent()).isBetween(50.0, 100.0);
        assertThat(report.factHits()).isGreaterThan(0);
        assertThat(report.factTokensInjected()).isGreaterThan(0);
    }

    @Test
    void factReuseReducesRepeatedToolCallsInTheControlledReplay() {
        assertThat(report.repeatedToolCallReductionPercent()).isGreaterThan(50.0);
        assertThat(report.memoryToolCalls()).isLessThan(report.baselineToolCalls());
    }

    private static BenchmarkReport simulate() {
        long fullHistoryTokens = 0;
        long boundedHistoryTokens = 0;
        long factTokensInjected = 0;
        int baselineToolCalls = 0;
        int memoryToolCalls = 0;
        int baselineRepeatedToolCalls = 0;
        int memoryRepeatedToolCalls = 0;
        int factHits = 0;
        int factReuseOpportunities = 0;

        for (int conversationIndex = 0; conversationIndex < CONVERSATIONS; conversationIndex++) {
            String conversationId = "memory-eval-" + conversationIndex;
            String fundCode = String.format(Locale.ROOT, "%06d", 1 + conversationIndex);
            var boundedMemory = new TokenBudgetChatMemory(
                    new InMemoryChatMemoryRepository(), MAX_HISTORY_TOKENS, MAX_MESSAGES);
            List<Message> fullHistory = new ArrayList<>();
            List<Fact> facts = new ArrayList<>();
            Map<String, Integer> priorCalls = new LinkedHashMap<>();
            Instant now = Instant.parse("2026-08-01T00:00:00Z").plus(Duration.ofDays(conversationIndex));

            for (int turn = 0; turn < TURNS_PER_CONVERSATION; turn++) {
                now = now.plus(Duration.ofMinutes(4));
                Turn request = turn(turn, fundCode);
                List<Fact> selected = selectFacts(facts, now);

                fullHistoryTokens += tokens(fullHistory);
                boundedHistoryTokens += tokens(boundedMemory.get(conversationId));
                factTokensInjected += selected.stream().mapToInt(fact -> fact.tokens).sum();

                for (String tool : request.requiredTools()) {
                    String key = tool + "|" + request.subject();
                    baselineToolCalls++;
                    int previous = priorCalls.getOrDefault(key, 0);
                    if (previous > 0) {
                        baselineRepeatedToolCalls++;
                        factReuseOpportunities++;
                    }

                    boolean hit = selected.stream().anyMatch(fact -> fact.key.equals(key));
                    if (hit) {
                        factHits++;
                    } else {
                        memoryToolCalls++;
                        if (previous > 0) memoryRepeatedToolCalls++;
                        facts.add(new Fact(key, now, now.plus(ttl(tool)), factTokens(tool, request.subject())));
                    }
                    priorCalls.put(key, previous + 1);
                }

                String answer = answer(request, turn);
                List<Message> pair = List.of(new UserMessage(request.question()), new AssistantMessage(answer));
                fullHistory.addAll(pair);
                boundedMemory.add(conversationId, pair);
            }
        }

        int turns = CONVERSATIONS * TURNS_PER_CONVERSATION;
        return new BenchmarkReport(
                "memory-efficiency-v1",
                "deterministic offline A/B replay; exact-key fact reuse, no live LLM",
                CONVERSATIONS,
                TURNS_PER_CONVERSATION,
                turns,
                fullHistoryTokens,
                boundedHistoryTokens,
                factTokensInjected,
                baselineToolCalls,
                memoryToolCalls,
                baselineRepeatedToolCalls,
                memoryRepeatedToolCalls,
                factHits,
                factReuseOpportunities,
                percentReduction(fullHistoryTokens, boundedHistoryTokens),
                percentReduction(fullHistoryTokens, boundedHistoryTokens + factTokensInjected),
                percent(factHits, factReuseOpportunities),
                percentReduction(baselineToolCalls, memoryToolCalls),
                percentReduction(baselineRepeatedToolCalls, memoryRepeatedToolCalls));
    }

    private static Turn turn(int index, String fundCode) {
        return switch (index % 12) {
            case 0 -> new Turn("查询 " + fundCode + " 的基本资料", fundCode, List.of("get_fund_profile"));
            case 1 -> new Turn("它最近一年的收益和最大回撤是多少", fundCode, List.of("calculate_fund_metrics"));
            case 2 -> new Turn("刚才的风险指标再解释一下", fundCode, List.of("calculate_fund_metrics"));
            case 3 -> new Turn("查看这只基金的历史净值走势", fundCode, List.of("get_fund_nav_history"));
            case 4 -> new Turn("把它与 110022 的风险收益做个比较", fundCode + ",110022", List.of("compare_fund_metrics"));
            case 5 -> new Turn("它最新季报中的投资策略是什么", fundCode, List.of("search_fund_documents"));
            case 6 -> new Turn("季报里如何解释组合波动", fundCode, List.of("search_fund_documents"));
            case 7 -> new Turn("查询一下当前实时行情", fundCode, List.of("get_fund_realtime_quote"));
            case 8 -> new Turn("刚才的实时价格是多少", fundCode, List.of("get_fund_realtime_quote"));
            case 9 -> new Turn("分析相关行业的近期观点", fundCode, List.of("analyze_sector_outlook"));
            case 10 -> new Turn("继续说明刚才的行业判断", fundCode, List.of("analyze_sector_outlook"));
            default -> new Turn("回到它的基本资料，基金类型是什么", fundCode, List.of("get_fund_profile"));
        };
    }

    private static List<Fact> selectFacts(List<Fact> facts, Instant now) {
        List<Fact> active = facts.stream()
                .filter(fact -> fact.expiresAt.isAfter(now))
                .sorted(Comparator.comparing(Fact::createdAt).reversed())
                .limit(MAX_FACT_CARDS)
                .toList();
        List<Fact> selected = new ArrayList<>();
        int used = 0;
        for (Fact fact : active) {
            if (used + fact.tokens > MAX_FACT_TOKENS) continue;
            selected.add(fact);
            used += fact.tokens;
        }
        return selected;
    }

    private static Duration ttl(String tool) {
        String name = tool.toLowerCase(Locale.ROOT);
        if (name.contains("realtime")) return Duration.ofMinutes(5);
        if (name.contains("sector") || name.contains("event") || name.contains("holding")
                || name.contains("impact") || name.contains("industry")) return Duration.ofHours(1);
        if (name.contains("document")) return Duration.ofDays(30);
        if (name.contains("profile")) return Duration.ofDays(7);
        return Duration.ofHours(24);
    }

    private static int factTokens(String tool, String subject) {
        String data = "{\"subject\":\"" + subject + "\",\"summary\":\"" + "经工具校验的基金事实数据。".repeat(8) + "\"}";
        String line = "FACT_CARD card tool=" + tool + " subject=" + subject
                + " evidenceIds=[ev-" + tool + "] expiresAt=2026-09-01T00:00:00Z data=" + data + "\n";
        return TokenBudgetChatMemory.estimateTokens(line);
    }

    private static String answer(Turn request, int turn) {
        return "第" + (turn + 1) + "轮分析基于 " + request.subject() + " 的可验证数据。"
                + "回答保留指标口径、数据区间、来源版本和证据编号，避免把历史表现解释为未来收益承诺。"
                + "如数据已经超过对应有效期，系统应重新调用工具，而不是继续沿用旧结论。"
                + "本轮所需能力为 " + String.join("、", request.requiredTools()) + "。";
    }

    private static long tokens(List<Message> messages) {
        return messages.stream().mapToLong(message -> TokenBudgetChatMemory.estimateTokens(message.getText())).sum();
    }

    private static double percentReduction(long baseline, long observed) {
        return baseline == 0 ? 0 : round(100.0 * (baseline - observed) / baseline);
    }

    private static double percent(int numerator, int denominator) {
        return denominator == 0 ? 0 : round(100.0 * numerator / denominator);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Turn(String question, String subject, List<String> requiredTools) {}

    private record Fact(String key, Instant createdAt, Instant expiresAt, int tokens) {}

    private record BenchmarkReport(
            String datasetVersion,
            String scope,
            int conversations,
            int turnsPerConversation,
            int turns,
            long fullHistoryTokens,
            long boundedHistoryTokens,
            long factTokensInjected,
            int baselineToolCalls,
            int memoryToolCalls,
            int baselineRepeatedToolCalls,
            int memoryRepeatedToolCalls,
            int factHits,
            int factReuseOpportunities,
            double historyTokenReductionPercent,
            double netMemoryPayloadReductionPercent,
            double factReuseHitRatePercent,
            double totalToolCallReductionPercent,
            double repeatedToolCallReductionPercent) {}
}
