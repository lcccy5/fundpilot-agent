package com.jijing.fund.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.FundAgentRequest;
import com.jijing.fund.agent.api.FundAgentUseCase;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.orchestration.AgentEvaluationFixtureContext;
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

/** Local-only evaluation target that executes the real Agent orchestration against deterministic data adapters. */
@RestController
@Profile("agent-eval")
@RequestMapping("/internal/v1/agent/evaluations")
public final class AgentEvaluationController {
    private final FundAgentUseCase agent;
    private final AgentRuntimeRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the profile-scoped endpoint and its observation readers. */
    public AgentEvaluationController(FundAgentUseCase agent, AgentRuntimeRepository repository,
                                     JdbcTemplate jdbc, ObjectMapper mapper) {
        this.agent = agent; this.repository = repository; this.jdbc = jdbc; this.mapper = mapper;
    }

    /** Executes one forced prompt version and returns tools, evidence, usage and latency. */
    @PostMapping("/execute")
    public AgentEvalObservation executeEvaluation(@RequestBody AgentEvalRequest request) {
        String conversationId = UUID.randomUUID().toString(); String requestId = "eval-" + UUID.randomUUID();
        repository.createConversation(conversationId, Instant.now()); Instant started = Instant.now();
        var result = AgentEvaluationFixtureContext.withFixture(request.fixtureId(), () -> agent.chat(new FundAgentRequest(conversationId,
                request.question() + "\n[本地评测数据夹具：" + request.fixtureId() + "]", requestId, null, request.promptVersion())));
        long elapsed = Duration.between(started, Instant.now()).toMillis();
        List<ToolObservation> tools = jdbc.query("select tool_name,arguments_redacted_json from agent_tool_call where run_id=? order by id",
                (rs, row) -> new ToolObservation(rs.getString(1), parse(rs.getString(2))), result.runId());
        List<String> evidence = result.evidence().stream().map(item -> item.evidenceType()).distinct().toList();
        return new AgentEvalObservation(result.runId(), result.answer(), tools, evidence,
                result.usage().promptTokens(), result.usage().completionTokens(), 0, elapsed);
    }

    private JsonNode parse(String value) { try { return value == null ? mapper.createObjectNode() : mapper.readTree(value); } catch (Exception ignored) { return mapper.createObjectNode(); } }

    /** Minimal request contract mirrored by AgentOps Worker. */
    public record AgentEvalRequest(String caseId, String promptVersion, String question, String fixtureId) { }
    /** Structured observation contract mirrored by AgentOps Worker. */
    public record AgentEvalObservation(String runId, String answer, List<ToolObservation> tools,
                                       List<String> evidenceTypes, long inputTokens, long outputTokens,
                                       long firstTokenMillis, long totalMillis) { }
    /** Actual model-selected tool and its redacted arguments. */
    public record ToolObservation(String name, JsonNode arguments) { }
}
