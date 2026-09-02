package com.jijing.fund.infrastructure.agent;

import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentChatMemoryRepository implements ChatMemoryRepository {
    private final JdbcTemplate jdbc;
    public JdbcAgentChatMemoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public List<String> findConversationIds(){return jdbc.query("SELECT conversation_id FROM agent_conversation WHERE status='ACTIVE' ORDER BY updated_at DESC",(rs,row)->rs.getString(1));}
    @Override public List<Message> findByConversationId(String id){return jdbc.query("SELECT role,content FROM agent_message WHERE conversation_id=? ORDER BY id",(rs,row)->toMessage(rs.getString("role"),rs.getString("content")),id);}
    @Override @Transactional public void saveAll(String id,List<Message> messages){
        jdbc.update("DELETE FROM agent_message WHERE conversation_id=?",id);Instant now=Instant.now();
        jdbc.batchUpdate("INSERT INTO agent_message(conversation_id,role,content,content_hash,token_count,created_at) VALUES(?,?,?,?,?,?)",new BatchPreparedStatementSetter(){
            @Override public void setValues(PreparedStatement ps,int index)throws SQLException{Message message=messages.get(index);String content=message.getText();ps.setString(1,id);ps.setString(2,message.getMessageType().name());ps.setString(3,content);ps.setString(4,AgentExecutionTrace.sha256(content));ps.setInt(5,com.jijing.fund.agent.orchestration.TokenBudgetChatMemory.estimateTokens(content));ps.setTimestamp(6,Timestamp.from(now.plusMillis(index)));}
            @Override public int getBatchSize(){return messages.size();}});
        jdbc.update("UPDATE agent_conversation SET message_count=?,updated_at=? WHERE conversation_id=?",messages.size(),Timestamp.from(now),id);
    }
    @Override @Transactional public void deleteByConversationId(String id){jdbc.update("DELETE FROM agent_message WHERE conversation_id=?",id);jdbc.update("UPDATE agent_conversation SET message_count=0,updated_at=? WHERE conversation_id=?",Timestamp.from(Instant.now()),id);}
    private Message toMessage(String role,String content){return switch(MessageType.valueOf(role)){case USER->new UserMessage(content);case SYSTEM->new SystemMessage(content);case ASSISTANT,TOOL->new AssistantMessage(content);};}
}
