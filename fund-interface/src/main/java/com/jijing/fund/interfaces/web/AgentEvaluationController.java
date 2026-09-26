package com.jijing.fund.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅在 agent-eval 配置下暴露的本地评测入口。
 * 真正的调用方校验在安全过滤器：缺少或错误的 {@code X-Agent-Eval-Token} 直接返回 401，且响应体不是统一信封。
 * 本方法不检查登录用户。请求体缺失或不是 JSON 时落入通用异常，目前返回 500。
 * 对话或模型失败按 Agent 异常映射，模型不可用为 503；工具参数无法解析时退回空对象，不让整次观测失败。
 */
@RestController
@Profile("agent-eval")
@RequestMapping("/internal/v1/agent/evaluations")
public final class AgentEvaluationController {
    private final FundAgentUseCase agent;
    private final AgentRuntimeRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * 绑定真实编排用例，以及读取工具调用所需的运行时仓储和数据库。
     */
    public AgentEvaluationController(FundAgentUseCase agent, AgentRuntimeRepository repository,
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.agent = agent;
        this.repository = repository;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 用指定提示词版本执行一道评测题，并回传工具、证据、用量和耗时。
     * 问题或夹具标识为空时仍会调用编排，不在此处返回 400。
     */
    @PostMapping("/execute")
    public AgentEvalObservation executeEvaluation(@RequestBody AgentEvalRequest request) {
        String conversationId = UUID.randomUUID().toString();
        String requestId = "eval-" + UUID.randomUUID();
        repository.createConversation(conversationId, Instant.now());
        Instant started = Instant.now();
        var result = AgentEvaluationFixtureContext.withFixture(request.fixtureId(), () -> agent.chat(
                new FundAgentRequest(conversationId,
                        request.question() + "\n[本地评测数据夹具：" + request.fixtureId() + "]",
                        requestId, null, request.promptVersion())));
        long elapsed = Duration.between(started, Instant.now()).toMillis();
        List<ToolObservation> tools = jdbc.query(
                "select tool_name,arguments_redacted_json from agent_tool_call where run_id=? order by id",
                (rs, row) -> new ToolObservation(rs.getString(1), parse(rs.getString(2))), result.runId());
        List<String> evidence = result.evidence().stream().map(item -> item.evidenceType()).distinct().toList();
        return new AgentEvalObservation(result.runId(), result.answer(), tools, evidence,
                result.usage().promptTokens(), result.usage().completionTokens(), 0, elapsed);
    }

    /**
     * 把已脱敏的工具参数还原成 JSON。损坏或空文本变成空对象，避免一次坏记录丢掉整次评测。
     */
    private JsonNode parse(String value) {
        try {
            return value == null ? mapper.createObjectNode() : mapper.readTree(value);
        } catch (Exception ignored) {
            return mapper.createObjectNode();
        }
    }

    /**
     * 与 AgentOps Worker 对齐的最小评测请求。四个字段都没有 Bean 校验。
     */
    public record AgentEvalRequest(String caseId, String promptVersion, String question, String fixtureId) {}

    /**
     * Worker 读取的观测结果。首 token 时延在本入口固定为 0，因为这里只测量整轮耗时。
     */
    public record AgentEvalObservation(String runId, String answer, List<ToolObservation> tools,
            List<String> evidenceTypes, long inputTokens, long outputTokens,
            long firstTokenMillis, long totalMillis) {}

    /**
     * 模型实际选择的工具及其脱敏参数。
     */
    public record ToolObservation(String name, JsonNode arguments) {}
}
