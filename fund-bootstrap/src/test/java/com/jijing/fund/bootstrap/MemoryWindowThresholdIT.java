package com.jijing.fund.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentResponse;
import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 用真实模型的长对话观察生产记忆窗口先碰到消息条数还是令牌预算，并检查早期事实在裁剪后是否仍被复用。默认构建不执行。
 */
@SpringBootTest(properties = {
        "fund.agent.enabled=true",
        "fund.knowledge.enabled=true",
        "fund.provider.type=mock",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles({"local", "test", "agent-eval"})
@EnabledIfEnvironmentVariable(named = "RUN_MEMORY_WINDOW_EVAL", matches = "true")
class MemoryWindowThresholdIT {
    private static final int MAX_MESSAGES = 20;
    private static final int MAX_TOKENS = 8000;

    @Autowired com.jijing.fund.agent.api.FundAgentUseCase agent;
    @Autowired AgentRuntimeRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    /**
     * 记录每一轮的库存消息和令牌，断言先触发的是消息条数上限。
     */
    @Test
    void identifiesTheFirstBindingWindowThresholdAndChecksLateFactReuse() throws Exception {
        String conversationId = UUID.randomUUID().toString();
        repository.createConversation(conversationId, Instant.now());
        List<TurnObservation> observations = new ArrayList<>();

        List<String> questions = questions();
        for (int turn = 0; turn < questions.size(); turn++) {
            String requestId = "memory-window-t" + turn + "-" + UUID.randomUUID();
            FundAgentResponse response = null;
            RuntimeException failure = null;
            String runId;
            try {
                int current = turn;
                response = AgentEvaluationFixtureContext.withFixture("memory-window-t" + turn,
                        () -> agent.chat(new FundAgentRequest(conversationId, questions.get(current), requestId)));
                runId = response.runId();
            } catch (RuntimeException ex) {
                failure = ex;
                runId = jdbc.queryForObject(
                        "select run_id from agent_run where request_id=? order by started_at desc limit 1",
                        String.class, requestId);
            }

            WindowState state = jdbc.queryForObject(
                    "select count(*),coalesce(sum(token_count),0) from agent_message where conversation_id=?",
                    (rs, row) -> new WindowState(rs.getInt(1), rs.getInt(2)), conversationId);
            int toolCalls = jdbc.queryForObject("select count(*) from agent_tool_call where run_id=?", Integer.class, runId);
            int profileToolCalls = jdbc.queryForObject(
                    "select count(*) from agent_tool_call where run_id=? and tool_name='get_fund_profile'",
                    Integer.class, runId);
            int profileFactsUsed = jdbc.queryForObject("""
                    select count(*) from agent_fact_card_usage u
                    join agent_fact_card c on c.card_id=u.card_id
                    where u.run_id=? and c.tool_name='get_fund_profile'
                    """, Integer.class, runId);
            observations.add(new TurnObservation(turn + 1, runId, state.messageCount(), state.tokenCount(),
                    toolCalls, profileToolCalls, profileFactsUsed,
                    response != null, failure == null ? null : failure.getClass().getSimpleName()));
        }

        TurnObservation firstTrimmed = observations.stream()
                .filter(item -> item.turn() > MAX_MESSAGES / 2 && item.storedMessages() <= MAX_MESSAGES)
                .findFirst().orElseThrow();
        String bindingThreshold = firstTrimmed.storedTokens() < MAX_TOKENS ? "MESSAGE_COUNT" : "TOKEN_BUDGET";
        TurnObservation finalTurn = observations.get(observations.size() - 1);
        boolean earlyProfileReusedAfterEviction = finalTurn.profileFactsUsed() > 0
                && finalTurn.profileToolCalls() == 0 && finalTurn.responseSucceeded();

        Report report = new Report("memory-window-threshold-v1", questions.size(), MAX_MESSAGES, MAX_TOKENS,
                firstTrimmed.turn(), bindingThreshold, firstTrimmed.storedMessages(), firstTrimmed.storedTokens(),
                earlyProfileReusedAfterEviction, List.copyOf(observations));
        Path target = Path.of("target", "memory-window-threshold-report.json");
        Files.createDirectories(target.getParent());
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), report);
        System.out.printf(Locale.ROOT,
                "MEMORY_WINDOW turns=%d firstTrimTurn=%d trigger=%s storedMessages=%d storedTokens=%d earlyFactReused=%s%n",
                report.turns(), report.firstTrimTurn(), report.bindingThreshold(), report.messagesAtFirstTrim(),
                report.tokensAtFirstTrim(), report.earlyProfileReusedAfterEviction());

        assertThat(observations).hasSize(questions.size());
        assertThat(bindingThreshold).isEqualTo("MESSAGE_COUNT");
        assertThat(firstTrimmed.storedTokens()).isLessThan(MAX_TOKENS);
    }

    /**
     * 按固定顺序给出会反复引用同一只基金的问题。
     */
    private static List<String> questions() {
        return List.of(
                "查询基金000001的基本资料并引用工具证据。",
                "查询它在2026年1月1日至2026年8月31日的收益、最大回撤和年化波动率，并引用工具证据。",
                "同一时间段内它的最大回撤是多少？请引用已有工具证据。",
                "同一时间段内它的年化波动率是多少？请引用已有工具证据。",
                "查询它在2026年1月1日至2026年8月31日的历史净值，并引用工具证据。",
                "刚才这只基金的期末累计净值是多少？请引用已有工具证据。",
                "回到刚才的指标，它在同一时间段的收益率是多少？请引用已有工具证据。",
                "它的基金类型是什么？请引用已有工具证据。",
                "它由哪家基金公司管理？请引用已有工具证据。",
                "它的基金经理是谁？请引用已有工具证据。",
                "再确认一次同一时间段的最大回撤，请引用已有工具证据。",
                "再确认一次同一时间段的年化波动率，请引用已有工具证据。",
                "再说明一次期末累计净值，请引用已有工具证据。",
                "比较刚才的收益率和最大回撤，并引用已有工具证据。",
                "现在对话已经很长了，请再次说明这只基金的基金类型，并引用已有工具证据。",
                "最后回到最开始的问题：它的基金公司和基金经理分别是谁？请引用已有工具证据。"
        );
    }

    /**
     * 某一时刻对话里保存的消息条数和令牌数。
     */
    record WindowState(int messageCount, int tokenCount) {}
    /**
     * 一轮对话的库存、工具次数和是否成功。
     */
    record TurnObservation(int turn, String runId, int storedMessages, int storedTokens, int toolCalls,
                           int profileToolCalls, int profileFactsUsed, boolean responseSucceeded, String errorType) {}
    /**
     * 写到目标目录的窗口阈值观察结果。
     */
    record Report(String datasetVersion, int turns, int maxMessages, int maxTokens, int firstTrimTurn,
                  String bindingThreshold, int messagesAtFirstTrim, int tokensAtFirstTrim,
                  boolean earlyProfileReusedAfterEviction, List<TurnObservation> observations) {}
}
