package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.TokenUsage;
import com.jijing.fund.agent.port.AgentRuntimeRepository;
import com.jijing.fund.agent.port.AgentToolCallRecord;
import com.jijing.fund.domain.identity.UserId;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话、运行、工具调用和事实卡的 JDBC 存储。
 * 事实卡按基金和记忆类别覆盖当前指针，历史卡行仍保留。事实卡使用记录用 {@code INSERT IGNORE}，重复记录同一运行和卡片不会再插一行。
 * 证据或字符串列表 JSON 读坏时变成空列表；写出失败时写成空数组。
 * 路由决策为 null 时直接返回。没有 HTTP 超时。
 */
@Repository
public class JdbcAgentRuntimeRepository implements AgentRuntimeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 不在构造时建会话。 */
    public JdbcAgentRuntimeRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 无主会话使用固定占位用户，状态为 ACTIVE，消息数为 0。 */
    @Override
    public void createConversation(String id, Instant now) {
        jdbc.update("""
                INSERT INTO agent_conversation(conversation_id,owner_user_id,status,message_count,created_at,updated_at)
                VALUES(?,?,?,?,?,?)
                """, id, "00000000-0000-0000-0000-000000000001", "ACTIVE", 0, ts(now), ts(now));
    }

    /** 带所有者和创建会话编号的会话，同样从 ACTIVE、零消息开始。 */
    @Override
    public void createConversation(String id, UserId owner, String session, Instant now) {
        jdbc.update("""
                INSERT INTO agent_conversation(conversation_id,owner_user_id,created_session_id,status,message_count,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, owner.value(), session, "ACTIVE", 0, ts(now), ts(now));
    }

    /** 只有 ACTIVE 计数大于 0 才算存在。计数查询为 null 时视为不存在。 */
    @Override
    public boolean conversationExists(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE conversation_id=? AND status='ACTIVE'",
                Integer.class, id);
        return count != null && count > 0;
    }

    /** 还必须属于该用户。 */
    @Override
    public boolean conversationExists(String id, UserId owner) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_conversation
                WHERE conversation_id=? AND owner_user_id=? AND status='ACTIVE'
                """, Integer.class, id, owner.value());
        return count != null && count > 0;
    }

    /** 请求编号为空时写成 unknown。运行一开始就是 RUNNING。 */
    @Override
    public String startRun(String conversationId, String requestId, String promptVersion, String promptHash,
            String toolSchemaVersion, String provider, String model, Instant startedAt) {
        String runId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run(run_id,conversation_id,request_id,prompt_version,prompt_hash,tool_schema_version,model_provider,model_name,status,started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, runId, conversationId, requestId == null ? "unknown" : requestId, promptVersion, promptHash,
                toolSchemaVersion, provider, model, "RUNNING", ts(startedAt));
        return runId;
    }

    /** 决策为空则不写。模型建议和覆盖原因超长时截断，避免列溢出。 */
    @Override
    public void recordRouteDecision(String runId, String ownerUserId,
            com.jijing.fund.agent.routing.RouteDecision decision, Instant createdAt) {
        if (decision == null) {
            return;
        }
        jdbc.update("""
                INSERT INTO agent_route_decision(decision_id,run_id,owner_user_id,execution_mode,direct_variant,router_version,features_json,matched_rule,model_suggestion,override_reason,created_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),?,?,?,?)
                """, UUID.randomUUID().toString(), runId, ownerUserId, decision.mode().name(),
                decision.directVariant() == null ? null : decision.directVariant().name(), decision.routerVersion(),
                json(decision.features()), decision.matchedRule(), truncate(decision.modelSuggestion(), 32),
                truncate(decision.overrideReason(), 128), ts(createdAt));
        jdbc.update("UPDATE agent_run SET execution_mode=?,router_version=?,route_reason=? WHERE run_id=?",
                decision.mode().name(), decision.routerVersion(), decision.matchedRule(), runId);
    }

    /** 只在父运行仍为空时填上，避免后写的升级覆盖已有父运行。 */
    @Override
    public void linkEscalatedRun(String childRunId, String parentRunId) {
        jdbc.update("UPDATE agent_run SET parent_run_id=? WHERE run_id=? AND parent_run_id IS NULL",
                parentRunId, childRunId);
    }

    /** 成功收尾，写入轮次、工具次数和 token。 */
    @Override
    public void completeRun(String runId, int rounds, int calls, TokenUsage usage, long duration, Instant completed) {
        jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',model_rounds=?,tool_call_count=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,completed_at=?,duration_ms=?
                WHERE run_id=?
                """, rounds, calls, usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
                ts(completed), duration, runId);
    }

    /** 失败状态和错误码由调用方给定，这里不判断能否重试。 */
    @Override
    public void failRun(String runId, String status, String code, String message, int calls, long duration,
            Instant completed) {
        jdbc.update("""
                UPDATE agent_run SET status=?,tool_call_count=?,error_code=?,safe_error_message=?,completed_at=?,duration_ms=?
                WHERE run_id=?
                """, status, calls, code, message, ts(completed), duration, runId);
    }

    /** 追加一条工具调用。证据列表序列化失败时写成空数组。 */
    @Override
    @Transactional
    public void recordToolCall(AgentToolCallRecord record) {
        jdbc.update("""
                INSERT INTO agent_tool_call(run_id,tool_name,tool_version,argument_hash,arguments_redacted_json,result_status,evidence_ids_json,error_code,duration_ms,started_at,completed_at)
                VALUES(?,?,?,?,CAST(? AS JSON),?,CAST(? AS JSON),?,?,?,?)
                """, record.runId(), record.toolName(), record.toolVersion(), record.argumentHash(),
                record.argumentsRedactedJson(), record.resultStatus(), json(record.evidenceIds()), record.errorCode(),
                record.durationMs(), ts(record.startedAt()), ts(record.completedAt()));
    }

    /**
     * 插入事实卡。主题为空则不更新当前指针。
     * 主题按逗号拆开，每个基金代码在同一会话和记忆类别上覆盖当前卡。
     */
    @Override
    @Transactional
    public void saveFactCard(AgentFactCard card) {
        jdbc.update("""
                INSERT INTO agent_fact_card(card_id,conversation_id,run_id,tool_name,subject_key,memory_category,evidence_ids_json,evidence_json,data_json,created_at,expires_at)
                VALUES(?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),?,?)
                """, card.cardId(), card.conversationId(), card.runId(), card.toolName(), card.subjectKey(),
                card.memoryCategory(), json(card.evidenceIds()), json(card.evidence()), card.dataJson(),
                ts(card.createdAt()), ts(card.expiresAt()));
        if (card.subjectKey() == null || card.subjectKey().isBlank()) {
            return;
        }
        for (String subject : card.subjectKey().split(",")) {
            String fundCode = subject.trim();
            if (fundCode.isEmpty()) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO agent_fund_memory_current(conversation_id,fund_code,memory_category,card_id,updated_at)
                    VALUES(?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE card_id=VALUES(card_id),updated_at=VALUES(updated_at)
                    """, card.conversationId(), fundCode, card.memoryCategory(), card.cardId(),
                    ts(card.createdAt()));
        }
    }

    /** 只返回未过期的当前卡，按更新时间倒序，条数至少为 1。 */
    @Override
    public List<AgentFactCard> findActiveFactCards(String conversationId, Instant now, int limit) {
        return jdbc.query("""
                SELECT c.card_id,c.conversation_id,c.run_id,c.tool_name,m.fund_code AS retrieval_subject,
                       c.evidence_json,c.data_json,c.created_at,c.expires_at
                FROM agent_fund_memory_current m
                JOIN agent_fact_card c ON c.card_id=m.card_id
                WHERE m.conversation_id=? AND c.expires_at>?
                ORDER BY m.updated_at DESC LIMIT ?
                """, (rs, row) -> new AgentFactCard(rs.getString("card_id"), rs.getString("conversation_id"),
                rs.getString("run_id"), rs.getString("tool_name"), rs.getString("retrieval_subject"),
                evidence(rs.getString("evidence_json")), rs.getString("data_json"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                conversationId, ts(now), Math.max(1, limit));
    }

    /** 同一运行重复引用同一张卡时忽略。 */
    @Override
    @Transactional
    public void recordFactCardUsage(String runId, List<String> cardIds, Instant usedAt) {
        for (String cardId : cardIds) {
            jdbc.update("INSERT IGNORE INTO agent_fact_card_usage(run_id,card_id,used_at) VALUES(?,?,?)",
                    runId, cardId, ts(usedAt));
        }
    }

    /** 没有状态行时返回空状态，而不是抛出未找到。 */
    @Override
    public AgentConversationState findConversationState(String conversationId) {
        List<AgentConversationState> rows = jdbc.query("""
                SELECT conversation_id,active_fund,mentioned_funds_json,period_start,period_end,active_topic,updated_at
                FROM agent_conversation_state WHERE conversation_id=?
                """, (rs, row) -> new AgentConversationState(rs.getString("conversation_id"),
                rs.getString("active_fund"), strings(rs.getString("mentioned_funds_json")),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class),
                rs.getString("active_topic"), rs.getTimestamp("updated_at").toInstant()), conversationId);
        return rows.stream().findFirst().orElse(AgentConversationState.empty(conversationId));
    }

    /** 有则更新全部字段，没有则插入。重复提交覆盖当前会话状态。 */
    @Override
    public void saveConversationState(AgentConversationState state) {
        jdbc.update("""
                INSERT INTO agent_conversation_state(conversation_id,active_fund,mentioned_funds_json,period_start,period_end,active_topic,updated_at)
                VALUES(?,?,CAST(? AS JSON),?,?,?,?)
                ON DUPLICATE KEY UPDATE active_fund=VALUES(active_fund),mentioned_funds_json=VALUES(mentioned_funds_json),
                    period_start=VALUES(period_start),period_end=VALUES(period_end),active_topic=VALUES(active_topic),updated_at=VALUES(updated_at)
                """, state.conversationId(), state.activeFund(), json(state.mentionedFunds()), state.periodStart(),
                state.periodEnd(), state.activeTopic(), ts(state.updatedAt()));
    }

    /** 证据 JSON 损坏时当成没有证据，避免一次坏行让整次回忆失败。 */
    private List<EvidenceReference> evidence(String value) {
        try {
            return mapper.readValue(value,
                    mapper.getTypeFactory().constructCollectionType(List.class, EvidenceReference.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 提及基金列表损坏时为空。 */
    private List<String> strings(String value) {
        try {
            return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    /** 写出失败退回空数组。 */
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    /** 超长文本截断。null 保持 null。 */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** JDBC 时间戳。 */
    private Timestamp ts(Instant value) {
        return Timestamp.from(value);
    }
}
