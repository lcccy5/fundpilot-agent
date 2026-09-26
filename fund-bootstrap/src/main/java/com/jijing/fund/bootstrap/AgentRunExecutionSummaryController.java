package com.jijing.fund.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.exception.AgentRunNotFoundException;
import com.jijing.fund.domain.identity.AuthenticatedUser;
import com.jijing.fund.domain.identity.UserRole;
import com.jijing.fund.interfaces.api.ApiResponse;
import com.jijing.fund.interfaces.web.CurrentUser;
import com.jijing.fund.interfaces.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * 把当前用户能看到的 Agent 运行轨迹，和 AgentOps 里的服务端记账投影拼在一起。
 * 匿名请求返回 401。运行不存在或不属于当前用户返回 404。
 * 本地库读取失败返回 500。AgentOps 不可达时不失败，字段标成不可用。
 */
@RestController
@RequestMapping("/api/v1/agent/runs")
public final class AgentRunExecutionSummaryController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RestClient agentOps;

    /**
     * 在服务端保存 AgentOps 管理凭据，避免浏览器直接持有它。
     */
    public AgentRunExecutionSummaryController(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            RestClient.Builder builder,
            @Value("${fund.agent.agentops.base-url:http://localhost:18080}") String baseUrl,
            @Value("${fund.agent.agentops.admin-token:local-admin-token}") String adminToken) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.agentOps = builder.baseUrl(baseUrl).defaultHeader("X-AgentOps-Admin-Token", adminToken).build();
    }

    /**
     * 只返回当前用户自己的运行，并附上不含原始工具参数的治理摘要。
     * 未登录返回 401；运行不属于该用户返回 404。记账服务失败时摘要仍返回，AgentOps 段标为不可用。
     */
    @GetMapping("/{runId}/queryExecutionSummary")
    public ApiResponse<Map<String, Object>> queryExecutionSummary(
            @CurrentUser AuthenticatedUser actor,
            @PathVariable("runId") String runId,
            HttpServletRequest request) {
        Map<String, Object> summary = queryOwnedRun(runId, actor.userId().value());
        summary.put("tools", queryTools(runId));
        summary.putAll(queryEvidenceSummary(runId));
        summary.put("agentOps", queryAgentOps(runId));
        if (actor.hasRole(UserRole.ADMIN)) {
            summary.put("adminConsoleUrl", "http://localhost:18080/console/requests/" + runId);
        }
        return ApiResponse.success(RequestIdFilter.get(request), summary);
    }

    /**
     * 在同一条查询里核对属主，避免先读出运行再判断归属时泄露他人诊断信息。查无此行时按运行不存在处理。
     */
    private Map<String, Object> queryOwnedRun(String runId, String ownerUserId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.run_id,r.status,r.prompt_version,r.model_provider,r.model_name,r.model_rounds,
                       r.tool_call_count,r.prompt_tokens,r.completion_tokens,r.total_tokens,
                       r.error_code,r.started_at,r.completed_at,r.duration_ms
                from agent_run r join agent_conversation c on c.conversation_id=r.conversation_id
                where r.run_id=? and c.owner_user_id=?
                """, runId, ownerUserId);
        if (rows.isEmpty()) throw new AgentRunNotFoundException("Agent run not found");
        return new LinkedHashMap<>(rows.getFirst());
    }

    /**
     * 只投影工具名、结果、耗时和证据条数，不把原始参数返回给浏览器。
     */
    private List<Map<String, Object>> queryTools(String runId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select tool_name,result_status,duration_ms,evidence_ids_json,error_code
                from agent_tool_call where run_id=? order by id
                """, runId);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("toolName", row.get("tool_name"));
            tool.put("status", row.get("result_status"));
            tool.put("durationMs", row.get("duration_ms"));
            tool.put("evidenceCount", jsonArraySize(row.get("evidence_ids_json")));
            tool.put("errorCode", row.get("error_code"));
            tools.add(tool);
        }
        return tools;
    }

    /**
     * 用已落库的证据推导数据截止日期和来源集合。单张损坏的事实卡被跳过，不让整份摘要失败。
     */
    private Map<String, Object> queryEvidenceSummary(String runId) {
        List<Map<String, Object>> cards = jdbc.queryForList(
                "select evidence_ids_json,evidence_json from agent_fact_card where run_id=? order by created_at", runId);
        Set<String> sources = new LinkedHashSet<>();
        LocalDate cutoff = null;
        int evidenceCount = 0;
        for (Map<String, Object> card : cards) {
            evidenceCount += jsonArraySize(card.get("evidence_ids_json"));
            try {
                JsonNode evidence = mapper.readTree(String.valueOf(card.get("evidence_json")));
                if (!evidence.isArray()) continue;
                for (JsonNode item : evidence) {
                    if (item.hasNonNull("dataSource")) sources.add(item.get("dataSource").asText());
                    LocalDate candidate = date(item, "actualEndDate");
                    if (candidate == null) candidate = date(item, "publishedDate");
                    if (candidate != null && (cutoff == null || candidate.isAfter(cutoff))) cutoff = candidate;
                }
            } catch (Exception malformedEvidence) {
                // 历史事实卡损坏时跳过该卡，已完成的回答仍然可以阅读。
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidenceCount", evidenceCount);
        result.put("dataCutoff", cutoff == null ? null : cutoff.toString());
        result.put("sources", List.copyOf(sources));
        return result;
    }

    /**
     * 读取关联的记账投影。下游超时、拒绝或空响应都降级为不可用，HTTP 仍按摘要成功返回。
     */
    private Map<String, Object> queryAgentOps(String runId) {
        try {
            @SuppressWarnings("unchecked") Map<String, Object> result = agentOps.get()
                    .uri("/internal/v1/usage/queryRun/{correlationId}", runId)
                    .retrieve().body(Map.class);
            return result == null ? Map.of("available", false) : withAvailability(result);
        } catch (RuntimeException unavailable) {
            return Map.of("available", false, "status", "UNAVAILABLE");
        }
    }

    /**
     * 在记账投影上标出可用，调用方不必再从空对象猜测是否成功。
     */
    private Map<String, Object> withAvailability(Map<String, Object> result) {
        Map<String, Object> enriched = new LinkedHashMap<>(result);
        enriched.put("available", true);
        return enriched;
    }

    /**
     * 统计 JSON 数组长度。空值或损坏文本按 0 处理，避免历史脏数据打断摘要。
     */
    private int jsonArraySize(Object json) {
        if (json == null) return 0;
        try {
            JsonNode node = mapper.readTree(String.valueOf(json));
            return node.isArray() ? node.size() : 0;
        } catch (Exception malformedJson) {
            return 0;
        }
    }

    /**
     * 读取证据上的日期字段。缺失或无法解析时返回空，交给调用方继续找下一个候选。
     */
    private LocalDate date(JsonNode node, String field) {
        if (!node.hasNonNull(field)) return null;
        try {
            return LocalDate.parse(node.get(field).asText());
        } catch (RuntimeException invalidDate) {
            return null;
        }
    }
}
