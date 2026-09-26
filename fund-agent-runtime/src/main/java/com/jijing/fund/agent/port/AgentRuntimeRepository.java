package com.jijing.fund.agent.port;

import com.jijing.fund.agent.api.AgentConversationState;
import com.jijing.fund.agent.api.AgentFactCard;
import com.jijing.fund.agent.api.TokenUsage;
import com.jijing.fund.agent.routing.RouteDecision;
import com.jijing.fund.domain.identity.UserId;
import java.time.Instant;
import java.util.List;

/**
 * 直接对话运行的存储端口：会话、运行结果、工具审计和短期记忆。
 * 未覆盖的默认方法会静默丢弃数据，调用方无法从返回值发现实现缺失。
 */
public interface AgentRuntimeRepository {

    /**
     * 登记一个新会话及其创建时间。
     * 标识冲突时的行为由实现决定；此重载不记录所有者。
     */
    void createConversation(String conversationId, Instant createdAt);

    /**
     * 登记带所有者和会话上下文的新会话。
     * 默认实现忽略所有者和会话上下文，只保留标识和时间，不会因为所有者为空而失败。
     */
    default void createConversation(String conversationId, UserId owner, String sessionId, Instant createdAt) {
        createConversation(conversationId, createdAt);
    }

    /**
     * 判断会话标识是否存在。
     * 不存在时返回 false；此重载不校验所有者。
     */
    boolean conversationExists(String conversationId);

    /**
     * 判断会话是否属于指定所有者。
     * 默认实现忽略所有者，只要标识存在就返回 true，因此不能用来做越权判断。
     */
    default boolean conversationExists(String conversationId, UserId owner) {
        return conversationExists(conversationId);
    }

    /**
     * 开始一次直接对话运行并返回运行标识。
     * 会话不存在时是否拒绝由实现决定；此方法不写入路由。
     */
    String startRun(
            String conversationId,
            String requestId,
            String promptVersion,
            String promptHash,
            String toolSchemaVersion,
            String modelProvider,
            String modelName,
            Instant startedAt);

    /**
     * 记录直接运行选定的路由。
     * 默认实现什么都不写；运行不存在时也不会失败。
     */
    default void recordRouteDecision(String runId, String ownerUserId, RouteDecision decision, Instant createdAt) {
    }

    /**
     * 把提升出来的持久化运行关联到触发它的有界运行。
     * 默认实现不建立关联，后续无法从子运行找回父运行。
     */
    default void linkEscalatedRun(String childRunId, String parentRunId) {
    }

    /**
     * 把运行标成完成并写入用量和耗时。
     * 运行不存在时的行为由实现决定。
     */
    void completeRun(String runId, int modelRounds, int toolCalls, TokenUsage usage, long durationMs, Instant completedAt);

    /**
     * 把运行标成给定的失败状态，并保存可对外展示的说明。
     * 运行不存在时的行为由实现决定；安全说明不应包含供应商原文。
     */
    void failRun(
            String runId,
            String status,
            String errorCode,
            String safeMessage,
            int toolCalls,
            long durationMs,
            Instant completedAt);

    /**
     * 追加一条工具调用审计。
     * 运行不存在时的行为由实现决定。
     */
    void recordToolCall(AgentToolCallRecord record);

    /**
     * 保存一张短期事实卡片。
     * 默认实现直接丢弃，不会因为卡片为空而失败。
     */
    default void saveFactCard(AgentFactCard card) {
    }

    /**
     * 查询会话中在给定时刻仍然有效的事实卡片。
     * 默认实现返回空列表，无法区分“没有卡片”和“实现未保存卡片”。
     */
    default List<AgentFactCard> findActiveFactCards(String conversationId, Instant now, int limit) {
        return List.of();
    }

    /**
     * 记录本次运行使用了哪些事实卡片。
     * 默认实现不记录；卡片标识为空时也不会失败。
     */
    default void recordFactCardUsage(String runId, List<String> cardIds, Instant usedAt) {
    }

    /**
     * 读取会话的跨轮指代状态。
     * 默认实现返回空状态，不表示会话不存在。
     */
    default AgentConversationState findConversationState(String conversationId) {
        return AgentConversationState.empty(conversationId);
    }

    /**
     * 保存会话的跨轮指代状态。
     * 默认实现直接丢弃，后续读取仍会得到空状态。
     */
    default void saveConversationState(AgentConversationState state) {
    }
}
