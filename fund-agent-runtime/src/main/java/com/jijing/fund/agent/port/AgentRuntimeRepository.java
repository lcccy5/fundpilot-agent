package com.jijing.fund.agent.port;

import com.jijing.fund.agent.api.*;
import java.time.Instant;
import java.util.List;
import com.jijing.fund.domain.identity.UserId;

/** 定义 AgentRuntimeRepository 在 Agent 运行时中的能力契约。 */
public interface AgentRuntimeRepository {
    
    /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
    void createConversation(String conversationId, Instant createdAt);
    default void createConversation(String conversationId,UserId owner,String sessionId,Instant createdAt){createConversation(conversationId,createdAt);}
    
    /** 执行该 Agent 运行时组件中的 conversationExists 操作。 */
    boolean conversationExists(String conversationId);
    default boolean conversationExists(String conversationId,UserId owner){return conversationExists(conversationId);}
    
    /** 创建并初始化当前 Agent 操作所需的 startRun 结果。 */
    String startRun(String conversationId, String requestId, String promptVersion, String promptHash,
            String toolSchemaVersion, String modelProvider, String modelName, Instant startedAt);
    
    /** 通过 completeRun 操作更新持久化或内存中的运行状态。 */
    void completeRun(String runId, int modelRounds, int toolCalls, TokenUsage usage, long durationMs, Instant completedAt);
    
    /** 执行该 Agent 运行时组件中的 failRun 操作。 */
    void failRun(String runId, String status, String errorCode, String safeMessage, int toolCalls,
            long durationMs, Instant completedAt);
    
    /** 通过 recordToolCall 操作更新持久化或内存中的运行状态。 */
    void recordToolCall(AgentToolCallRecord record);
    default void saveFactCard(AgentFactCard card) {}
    default List<AgentFactCard> findActiveFactCards(String conversationId, Instant now, int limit) { return List.of(); }
    default void recordFactCardUsage(String runId, List<String> cardIds, Instant usedAt) {}
}
