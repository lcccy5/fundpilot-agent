package com.jijing.fund.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentResponse;
import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 对比无状态独立提问和完整记忆设计。默认构建不执行。
 */
@SpringBootTest(properties = {
        "fund.agent.enabled=true",
        "fund.knowledge.enabled=true",
        "fund.provider.type=mock",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles({"local", "test", "agent-eval"})
@Import(MemoryRealModelAbIT.TokenObservationConfiguration.class)
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_AB_EVAL", matches = "true")
class MemoryRealModelAbIT {
    @Autowired com.jijing.fund.agent.api.FundAgentUseCase agent;
    @Autowired AgentRuntimeRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ModelTokenAccumulator tokenAccumulator;

    /**
     * 按场景重复同一组追问，写出令牌和重复工具的变化。
     */
    @Test
    void comparesStatelessAndCompleteMemoryWithTheConfiguredRealModel() throws Exception {
        int scenarioCount = Integer.getInteger("memory.eval.scenarios", 2);
        assertThat(scenarioCount).isBetween(1, 20);
        List<Turn> turns = turns();
        List<RunObservation> baseline = new ArrayList<>();
        List<RunObservation> memory = new ArrayList<>();

        for (int scenario = 0; scenario < scenarioCount; scenario++) {
            String fundCode = String.format(Locale.ROOT, "%06d", scenario + 1);
            String memoryConversation = createConversation();
            Set<String> baselineSignatures = new HashSet<>();
            Set<String> memorySignatures = new HashSet<>();
            for (int turnIndex = 0; turnIndex < turns.size(); turnIndex++) {
                Turn turn = turns.get(turnIndex);
                String fixture = "memory-ab-s" + scenario + "-t" + turnIndex;
                baseline.add(execute("A", scenario, turnIndex, createConversation(),
                        turn.standaloneQuestion().formatted(fundCode), fixture, turn, baselineSignatures));
                memory.add(execute("C", scenario, turnIndex, memoryConversation,
                        turn.contextualQuestion().formatted(fundCode), fixture, turn, memorySignatures));
            }
        }

        Report report = report(scenarioCount, turns.size(), baseline, memory);
        Path target = Path.of("target", "memory-real-model-ab-report.json");
        Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        System.out.printf(Locale.ROOT,
                "MEMORY_REAL_AB scenarios=%d turnsPerGroup=%d comparable=%d/%d promptTokenChange=%.2f%% totalTokenChange=%.2f%% factHitRate=%.2f%% repeatedToolCallReduction=%.2f%%%n",
                report.scenarios(), report.turnsPerGroup(), report.comparableTurns(), report.turnsPerGroup(),
                report.promptTokenChangePercent(), report.totalTokenChangePercent(), report.factCardEffectiveHitRatePercent(),
                report.repeatedToolCallReductionPercent());

        assertThat(baseline).hasSize(report.turnsPerGroup());
        assertThat(memory).hasSize(report.turnsPerGroup());
        assertThat(report.baseline().observedModelCalls()).isGreaterThanOrEqualTo(report.turnsPerGroup());
        assertThat(report.memory().observedModelCalls()).isGreaterThanOrEqualTo(report.turnsPerGroup());
    }

    /**
     * 执行一轮并判断事实卡是否在不再调用工具的情况下保住了答案质量。
     */
    private RunObservation execute(String group, int scenario, int turnIndex, String conversationId,
                                   String question, String fixture, Turn turn, Set<String> priorSignatures) {
        String requestId = "memory-real-ab-" + group + "-" + scenario + "-" + turnIndex + "-" + UUID.randomUUID();
        FundAgentResponse response;
        RuntimeException failure = null;
        String runId;
        try {
            response = AgentEvaluationFixtureContext.withFixture(fixture,
                    () -> agent.chat(new FundAgentRequest(conversationId, question, requestId)));
            runId = response.runId();
        } catch (RuntimeException ex) {
            response = null;
            failure = ex;
            runId = jdbc.queryForObject("select run_id from agent_run where request_id=? order by started_at desc limit 1",
                    String.class, requestId);
        }
        TokenTotals tokens = tokenAccumulator.remove(runId);
        List<ToolCall> calls = jdbc.query(
                "select tool_name,argument_hash from agent_tool_call where run_id=? order by id",
                (rs, row) -> new ToolCall(rs.getString(1), rs.getString(2)), runId);
        Set<String> usedFactTools = new HashSet<>(jdbc.query(
                "select c.tool_name from agent_fact_card_usage u join agent_fact_card c on c.card_id=u.card_id where u.run_id=?",
                (rs, row) -> rs.getString(1), runId));
        int repeatedCalls = 0;
        for (ToolCall call : calls) {
            String signature = call.name() + "|" + call.argumentHash();
            if (!priorSignatures.add(signature)) repeatedCalls++;
        }
        boolean expectedEvidence = response != null && response.evidence().stream().map(EvidenceReference::evidenceType)
                .anyMatch(turn.expectedEvidenceType()::equals);
        boolean qualityPass = response != null && response.answer() != null && !response.answer().isBlank() && expectedEvidence;
        boolean calledExpectedTool = calls.stream().anyMatch(call -> call.name().equals(turn.expectedTool()));
        boolean usedExpectedFact = usedFactTools.contains(turn.expectedTool());
        boolean effectiveFactHit = turn.reuseOpportunity() && usedExpectedFact && !calledExpectedTool && qualityPass;
        String answer = response == null ? null : response.answer();
        String error = failure == null ? null : failure.getClass().getSimpleName() + ": " + failure.getMessage();
        return new RunObservation(group, scenario, turnIndex, runId, question, answer, error,
                tokens, calls, repeatedCalls, usedFactTools, turn.reuseOpportunity(), effectiveFactHit, qualityPass);
    }

    /**
     * 新建一条空对话。
     */
    private String createConversation() {
        String id = UUID.randomUUID().toString();
        repository.createConversation(id, Instant.now());
        return id;
    }

    /**
     * 给出资料、指标和净值的独立问法与依赖上文的问法。
     */
    private static List<Turn> turns() {
        return List.of(
                new Turn("查询基金 %s 的基本资料并引用工具证据。", "查询基金 %s 的基本资料并引用工具证据。",
                        "get_fund_profile", "FUND_PROFILE", false),
                new Turn("查询基金 %s 在2026年1月1日至2026年8月31日的收益、最大回撤和年化波动率，并引用工具证据。",
                        "查询这只基金在2026年1月1日至2026年8月31日的收益、最大回撤和年化波动率，并引用工具证据。",
                        "calculate_fund_metrics", "FUND_METRICS", false),
                new Turn("基金 %s 在2026年1月1日至2026年8月31日的最大回撤是多少？请引用工具证据。",
                        "同一时间段内，它的最大回撤是多少？请引用已有的工具证据。",
                        "calculate_fund_metrics", "FUND_METRICS", true),
                new Turn("基金 %s 在2026年1月1日至2026年8月31日的年化波动率是多少？请引用工具证据。",
                        "同一时间段内，它的年化波动率是多少？请引用已有的工具证据。",
                        "calculate_fund_metrics", "FUND_METRICS", true),
                new Turn("查询基金 %s 在2026年1月1日至2026年8月31日的历史净值，并引用工具证据。",
                        "再查询它在2026年1月1日至2026年8月31日的历史净值，并引用工具证据。",
                        "get_fund_nav_history", "FUND_NAV", false),
                new Turn("基金 %s 在2026年8月31日的期末累计净值是多少？请引用工具证据。",
                        "刚才这只基金的期末累计净值是多少？请引用已有的工具证据。",
                        "get_fund_nav_history", "FUND_NAV", true),
                new Turn("再次说明基金 %s 在2026年1月1日至2026年8月31日的收益率，并引用工具证据。",
                        "回到刚才的指标，它在同一时间段的收益率是多少？请引用已有的工具证据。",
                        "calculate_fund_metrics", "FUND_METRICS", true),
                new Turn("基金 %s 的基金类型、基金公司和基金经理分别是什么？请引用工具证据。",
                        "最后说明这只基金的类型、基金公司和基金经理，并引用已有的工具证据。",
                        "get_fund_profile", "FUND_PROFILE", true));
    }

    /**
     * 汇总两侧用量，并按场景列出令牌变化。
     */
    private static Report report(int scenarios, int turnsPerScenario,
                                 List<RunObservation> baseline, List<RunObservation> memory) {
        GroupMetrics a = metrics(baseline);
        GroupMetrics c = metrics(memory);
        int comparable = 0;
        for (int i = 0; i < baseline.size(); i++) if (baseline.get(i).qualityPass() && memory.get(i).qualityPass()) comparable++;
        long reuseOpportunities = memory.stream().filter(RunObservation::reuseOpportunity).count();
        long factHits = memory.stream().filter(RunObservation::effectiveFactHit).count();
        List<ScenarioComparison> comparisons = new ArrayList<>();
        for (int scenario = 0; scenario < scenarios; scenario++) {
            int current = scenario;
            GroupMetrics scenarioA = metrics(baseline.stream().filter(item -> item.scenario() == current).toList());
            GroupMetrics scenarioC = metrics(memory.stream().filter(item -> item.scenario() == current).toList());
            comparisons.add(new ScenarioComparison(scenario, scenarioA, scenarioC,
                    change(scenarioA.promptTokens(), scenarioC.promptTokens()),
                    change(scenarioA.totalTokens(), scenarioC.totalTokens()),
                    reduction(scenarioA.repeatedToolCalls(), scenarioC.repeatedToolCalls())));
        }
        return new Report("memory-real-model-ab-v1", scenarios, turnsPerScenario,
                baseline.size(), comparable, a, c,
                change(a.promptTokens(), c.promptTokens()), change(a.totalTokens(), c.totalTokens()),
                percent(factHits, reuseOpportunities), reduction(a.repeatedToolCalls(), c.repeatedToolCalls()),
                List.copyOf(comparisons),
                "A uses a fresh conversation and standalone question per turn; C uses one conversation and contextual questions with production chat/fact memory");
    }

    /**
     * 把观察加成模型调用、令牌、工具次数和质量通过率。
     */
    private static GroupMetrics metrics(List<RunObservation> observations) {
        long prompt = observations.stream().map(RunObservation::tokens).mapToLong(TokenTotals::promptTokens).sum();
        long completion = observations.stream().map(RunObservation::tokens).mapToLong(TokenTotals::completionTokens).sum();
        long modelCalls = observations.stream().map(RunObservation::tokens).mapToLong(TokenTotals::modelCalls).sum();
        long toolCalls = observations.stream().mapToLong(item -> item.toolCalls().size()).sum();
        long repeated = observations.stream().mapToLong(RunObservation::repeatedToolCalls).sum();
        long passed = observations.stream().filter(RunObservation::qualityPass).count();
        return new GroupMetrics(observations.size(), modelCalls, prompt, completion, prompt + completion,
                toolCalls, repeated, percent(passed, observations.size()));
    }

    /**
     * 计算从基线到记忆方案的百分比变化。
     */
    private static double change(long baseline, long memory) {
        return baseline == 0 ? 0 : round(100.0 * (memory - baseline) / baseline);
    }

    /**
     * 计算记忆方案相对基线减少的百分比。
     */
    private static double reduction(long baseline, long memory) {
        return baseline == 0 ? 0 : round(100.0 * (baseline - memory) / baseline);
    }

    /**
     * 计算百分比。分母为零时记为零。
     */
    private static double percent(long numerator, long denominator) {
        return denominator == 0 ? 0 : round(100.0 * numerator / denominator);
    }

    /**
     * 保留两位小数。
     */
    private static double round(double value) { return Math.round(value * 100.0) / 100.0; }

    /**
     * 独立问法、上下文问法，以及期望工具和是否存在复用机会。
     */
    record Turn(String standaloneQuestion, String contextualQuestion, String expectedTool,
                String expectedEvidenceType, boolean reuseOpportunity) {}
    /**
     * 一次工具调用的名称和参数摘要。
     */
    record ToolCall(String name, String argumentHash) {}
    /**
     * 一轮真实模型调用的用量、质量和事实复用结果。
     */
    record RunObservation(String group, int scenario, int turnIndex, String runId, String question, String answer, String error,
                          TokenTotals tokens, List<ToolCall> toolCalls, int repeatedToolCalls, Set<String> usedFactTools,
                          boolean reuseOpportunity, boolean effectiveFactHit, boolean qualityPass) {}
    /**
     * 一组运行的合计用量和质量通过率。
     */
    record GroupMetrics(int turns, long observedModelCalls, long promptTokens, long completionTokens, long totalTokens,
                        long toolCalls, long repeatedToolCalls, double qualityPassRatePercent) {}
    /**
     * 单个场景两侧的用量差。
     */
    record ScenarioComparison(int scenario, GroupMetrics baseline, GroupMetrics memory,
                              double promptTokenChangePercent, double totalTokenChangePercent,
                              double repeatedToolCallReductionPercent) {}
    /**
     * 完整记忆对比实验的结果和方法说明。
     */
    record Report(String datasetVersion, int scenarios, int turnsPerScenario, int turnsPerGroup, int comparableTurns,
                  GroupMetrics baseline, GroupMetrics memory, double promptTokenChangePercent,
                  double totalTokenChangePercent, double factCardEffectiveHitRatePercent,
                  double repeatedToolCallReductionPercent, List<ScenarioComparison> scenarioComparisons,
                  String methodology) {}

    /**
     * 从聊天模型观测里按运行汇总提示词和补全令牌。
     */
    static final class ModelTokenAccumulator implements ObservationHandler<ChatModelObservationContext> {
        private final Map<String, MutableTotals> totals = new ConcurrentHashMap<>();

        /**
         * 只接收聊天模型观测，忽略其它观测上下文。
         */
        @Override public boolean supportsContext(Observation.Context context) {
            return context instanceof ChatModelObservationContext;
        }

        /**
         * 在一次模型调用结束时把用量加到对应运行上。缺少运行号或用量时跳过。
         */
        @Override public void onStop(ChatModelObservationContext context) {
            String runId = runId(context.getRequest());
            ChatResponse response = context.getResponse();
            if (runId == null || response == null || response.getMetadata().getUsage() == null) return;
            var usage = response.getMetadata().getUsage();
            totals.computeIfAbsent(runId, ignored -> new MutableTotals()).add(
                    value(usage.getPromptTokens()), value(usage.getCompletionTokens()));
        }

        /**
         * 取出并清掉某次运行的合计。没有观测记录时失败，避免把缺失当成零用量。
         */
        TokenTotals remove(String runId) {
            MutableTotals value = totals.remove(runId);
            if (value == null) throw new IllegalStateException("No model observations captured for run " + runId);
            return new TokenTotals(value.modelCalls, value.promptTokens, value.completionTokens);
        }

        /**
         * 从模型请求头读取运行关联号。没有该头时无法归因。
         */
        private static String runId(Prompt prompt) {
            if (!(prompt.getOptions() instanceof OpenAiChatOptions options) || options.getHttpHeaders() == null) return null;
            return options.getHttpHeaders().get("X-AgentOps-Correlation-Id");
        }

        /**
         * 空的令牌计数按零处理。
         */
        private static long value(Integer value) { return value == null ? 0 : value.longValue(); }

        /**
         * 一次运行上可并发累加的模型调用次数和令牌数。
         */
        private static final class MutableTotals {
            private long modelCalls;
            private long promptTokens;
            private long completionTokens;
            /**
             * 把一次模型调用的提示词和补全令牌加进合计。
             */
            synchronized void add(long prompt, long completion) {
                modelCalls++;
                promptTokens += prompt;
                completionTokens += completion;
            }
        }
    }

    /**
     * 一次运行已经结束的模型调用次数和令牌数。
     */
    record TokenTotals(long modelCalls, long promptTokens, long completionTokens) {}

    /**
     * 把令牌累加器注册成测试配置中的观测处理器。
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TokenObservationConfiguration {
        /**
         * 创建供两个记忆实验共用的累加器。
         */
        @Bean ModelTokenAccumulator modelTokenAccumulator() { return new ModelTokenAccumulator(); }
    }
}
