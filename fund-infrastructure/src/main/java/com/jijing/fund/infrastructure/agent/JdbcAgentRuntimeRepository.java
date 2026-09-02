package com.jijing.fund.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.EvidenceReference;
import com.jijing.fund.agent.api.TokenUsage;
import com.jijing.fund.agent.port.*;
import com.jijing.fund.domain.identity.UserId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentRuntimeRepository implements AgentRuntimeRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public JdbcAgentRuntimeRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    @Override public void createConversation(String id,Instant now){jdbc.update("INSERT INTO agent_conversation(conversation_id,owner_user_id,status,message_count,created_at,updated_at) VALUES(?,?,?,?,?,?)",id,"00000000-0000-0000-0000-000000000001","ACTIVE",0,ts(now),ts(now));}
    @Override public void createConversation(String id,UserId owner,String session,Instant now){jdbc.update("INSERT INTO agent_conversation(conversation_id,owner_user_id,created_session_id,status,message_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",id,owner.value(),session,"ACTIVE",0,ts(now),ts(now));}
    @Override public boolean conversationExists(String id){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE conversation_id=? AND status='ACTIVE'",Integer.class,id);return count!=null&&count>0;}
    @Override public boolean conversationExists(String id,UserId owner){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE conversation_id=? AND owner_user_id=? AND status='ACTIVE'",Integer.class,id,owner.value());return count!=null&&count>0;}
    @Override public String startRun(String conversationId,String requestId,String promptVersion,String promptHash,String toolSchemaVersion,String provider,String model,Instant startedAt){String runId=UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run(run_id,conversation_id,request_id,prompt_version,prompt_hash,tool_schema_version,model_provider,model_name,status,started_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,runId,conversationId,requestId==null?"unknown":requestId,promptVersion,promptHash,toolSchemaVersion,provider,model,"RUNNING",ts(startedAt));return runId;}
    @Override public void completeRun(String runId,int rounds,int calls,TokenUsage usage,long duration,Instant completed){jdbc.update("""
            UPDATE agent_run SET status='SUCCEEDED',model_rounds=?,tool_call_count=?,prompt_tokens=?,completion_tokens=?,total_tokens=?,completed_at=?,duration_ms=? WHERE run_id=?
            """,
            rounds,calls,usage.promptTokens(),usage.completionTokens(),usage.totalTokens(),ts(completed),duration,runId);}
    @Override public void failRun(String runId,String status,String code,String message,int calls,long duration,Instant completed){jdbc.update("""
            UPDATE agent_run SET status=?,tool_call_count=?,error_code=?,safe_error_message=?,completed_at=?,duration_ms=? WHERE run_id=?
            """,status,calls,code,message,ts(completed),duration,runId);}
    @Override @Transactional public void recordToolCall(AgentToolCallRecord r){jdbc.update("""
            INSERT INTO agent_tool_call(run_id,tool_name,tool_version,argument_hash,arguments_redacted_json,result_status,evidence_ids_json,error_code,duration_ms,started_at,completed_at)
            VALUES(?,?,?,?,CAST(? AS JSON),?,CAST(? AS JSON),?,?,?,?)
            """,r.runId(),r.toolName(),r.toolVersion(),r.argumentHash(),r.argumentsRedactedJson(),r.resultStatus(),json(r.evidenceIds()),r.errorCode(),r.durationMs(),ts(r.startedAt()),ts(r.completedAt()));}
    @Override public void saveFactCard(AgentFactCard card){jdbc.update("""
            INSERT INTO agent_fact_card(card_id,conversation_id,run_id,tool_name,subject_key,evidence_ids_json,evidence_json,data_json,created_at,expires_at)
            VALUES(?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),CAST(? AS JSON),?,?)
            """,card.cardId(),card.conversationId(),card.runId(),card.toolName(),card.subjectKey(),json(card.evidenceIds()),json(card.evidence()),
            card.dataJson(),ts(card.createdAt()),ts(card.expiresAt()));}
    @Override public List<AgentFactCard> findActiveFactCards(String conversationId,Instant now,int limit){return jdbc.query("""
            SELECT card_id,conversation_id,run_id,tool_name,subject_key,evidence_json,data_json,created_at,expires_at
            FROM agent_fact_card WHERE conversation_id=? AND expires_at>? ORDER BY created_at DESC LIMIT ?
            """,(rs,row)->new AgentFactCard(rs.getString("card_id"),rs.getString("conversation_id"),rs.getString("run_id"),
                    rs.getString("tool_name"),rs.getString("subject_key"),evidence(rs.getString("evidence_json")),
                    rs.getString("data_json"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("expires_at").toInstant()),
            conversationId,ts(now),Math.max(1,limit));}
    @Override @Transactional public void recordFactCardUsage(String runId,List<String> cardIds,Instant usedAt){for(String cardId:cardIds)jdbc.update(
            "INSERT IGNORE INTO agent_fact_card_usage(run_id,card_id,used_at) VALUES(?,?,?)",runId,cardId,ts(usedAt));}
    private List<EvidenceReference> evidence(String value){try{return mapper.readValue(value,mapper.getTypeFactory().constructCollectionType(List.class,EvidenceReference.class));}catch(Exception ex){return List.of();}}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){return "[]";}}
    private Timestamp ts(Instant value){return Timestamp.from(value);}
}
