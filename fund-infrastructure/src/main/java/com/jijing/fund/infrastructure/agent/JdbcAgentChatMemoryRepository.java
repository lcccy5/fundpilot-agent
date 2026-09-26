package com.jijing.fund.infrastructure.agent;

import com.jijing.fund.agent.orchestration.AgentExecutionTrace;
import com.jijing.fund.agent.orchestration.TokenBudgetChatMemory;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话消息的 JDBC 存储。保存时先删掉该会话的全部消息再整批插入，重复保存是替换而不是追加。
 * 工具消息读回时当成助手消息。没有 HTTP 超时；数据库连接失败由 Spring 抛出。
 */
@Repository
public class JdbcAgentChatMemoryRepository implements ChatMemoryRepository {
    private final JdbcTemplate jdbc;

    /** 不在构造时读消息。 */
    public JdbcAgentChatMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 只列出仍为 ACTIVE 的会话，按更新时间倒序。 */
    @Override
    public List<String> findConversationIds() {
        return jdbc.query(
                "SELECT conversation_id FROM agent_conversation WHERE status='ACTIVE' ORDER BY updated_at DESC",
                (rs, row) -> rs.getString(1));
    }

    /** 按插入顺序返回。角色无法识别时 {@link MessageType#valueOf} 会抛错。 */
    @Override
    public List<Message> findByConversationId(String id) {
        return jdbc.query("SELECT role,content FROM agent_message WHERE conversation_id=? ORDER BY id",
                (rs, row) -> toMessage(rs.getString("role"), rs.getString("content")), id);
    }

    /**
     * 整表替换该会话的消息，并用序号错开时间戳以保持顺序。会话上的条数和更新时间一起改写。
     */
    @Override
    @Transactional
    public void saveAll(String id, List<Message> messages) {
        jdbc.update("DELETE FROM agent_message WHERE conversation_id=?", id);
        Instant now = Instant.now();
        jdbc.batchUpdate(
                "INSERT INTO agent_message(conversation_id,role,content,content_hash,token_count,created_at) VALUES(?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    /** 哈希和 token 估计沿用运行时的同一套算法，避免存储和预算不一致。 */
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        Message message = messages.get(index);
                        String content = message.getText();
                        statement.setString(1, id);
                        statement.setString(2, message.getMessageType().name());
                        statement.setString(3, content);
                        statement.setString(4, AgentExecutionTrace.sha256(content));
                        statement.setInt(5, TokenBudgetChatMemory.estimateTokens(content));
                        statement.setTimestamp(6, Timestamp.from(now.plusMillis(index)));
                    }

                    /** 批次大小等于本次要保存的条数，空列表则不插入。 */
                    @Override
                    public int getBatchSize() {
                        return messages.size();
                    }
                });
        jdbc.update("UPDATE agent_conversation SET message_count=?,updated_at=? WHERE conversation_id=?",
                messages.size(), Timestamp.from(now), id);
    }

    /** 删除消息并把会话条数清零。会话行本身保留。 */
    @Override
    @Transactional
    public void deleteByConversationId(String id) {
        jdbc.update("DELETE FROM agent_message WHERE conversation_id=?", id);
        jdbc.update("UPDATE agent_conversation SET message_count=0,updated_at=? WHERE conversation_id=?",
                Timestamp.from(Instant.now()), id);
    }

    /** USER、SYSTEM 保持原类型；ASSISTANT 和 TOOL 都读成助手消息。 */
    private Message toMessage(String role, String content) {
        return switch (MessageType.valueOf(role)) {
            case USER -> new UserMessage(content);
            case SYSTEM -> new SystemMessage(content);
            case ASSISTANT, TOOL -> new AssistantMessage(content);
        };
    }
}
