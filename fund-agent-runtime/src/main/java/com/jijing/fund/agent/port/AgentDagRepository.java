package com.jijing.fund.agent.port;

import com.jijing.fund.agent.api.*;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.routing.RouteDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 定义 AgentDagRepository 在 Agent 运行时中的能力契约。 */
public interface AgentDagRepository {
    
    /** 创建并初始化当前 Agent 操作所需的 createConversation 结果。 */
    String createConversation(String ownerUserId,Instant now);
    
    /** 创建并初始化当前 Agent 操作所需的 startRun 结果。 */
    String startRun(String conversationId,String ownerUserId,String requestId,String executionMode,String routeReason,Instant now);
    
    /** 通过 saveRoute 操作更新持久化或内存中的运行状态。 */
    void saveRoute(String runId,String ownerUserId,RouteDecision decision,Instant now);
    
    /** 通过 saveValidatedPlan 操作更新持久化或内存中的运行状态。 */
    String saveValidatedPlan(String runId,String ownerUserId,PlanDraft draft,Instant now);
    
    /** 获取当前 Agent 操作所需的 findRun 结果。 */
    Optional<AgentRunView> findRun(String runId);
    
    /** 执行该 Agent 运行时组件中的 requireOwnedRun 操作。 */
    AgentRunView requireOwnedRun(String runId,String ownerUserId);
    
    /** 执行该 Agent 运行时组件中的 requireOwnedPlan 操作。 */
    AgentPlanView requireOwnedPlan(String runId,String ownerUserId);
    
    /** 获取当前 Agent 操作所需的 eventsAfter 结果。 */
    List<AgentRunEventView> eventsAfter(String runId,String ownerUserId,long lastSequence);
    
    /** 通过 appendEvent 操作更新持久化或内存中的运行状态。 */
    void appendEvent(String runId,String type,String payloadJson,Instant now);
    
    /** 通过 claimReady 操作更新持久化或内存中的运行状态。 */
    Optional<ClaimedTask> claimReady(String workerId,Instant now,Duration lease);
    
    /** 通过 completeTask 操作更新持久化或内存中的运行状态。 */
    void completeTask(String taskId,String executionKey,String outputUri,List<String> evidenceIds,Instant now);
    
    /** 通过 markWaitingApproval 操作更新持久化或内存中的运行状态。 */
    void markWaitingApproval(String taskId,String approvalId,Instant now);
    
    /** 通过 markTaskReady 操作更新持久化或内存中的运行状态。 */
    void markTaskReady(String taskId);
    
    /** 执行 cancelRun 对应的资源状态转换。 */
    void cancelRun(String runId,String ownerUserId,Instant now);
    
    /** 执行该 Agent 运行时组件中的 requestApproval 操作。 */
    String requestApproval(String runId,String taskId,String ownerUserId,String actionType,String parameterHash,String summary,Instant expiresAt,Instant now);
    
    /** 执行 consumeApproval 操作，并应用相应的 Agent 运行时状态变化。 */
    boolean consumeApproval(String approvalId,String ownerUserId,String expectedHash,Instant now);
    
    /** 执行 rejectApproval 对应的资源状态转换。 */
    void rejectApproval(String approvalId,String ownerUserId,Instant now);
    
    /** 执行 recoverExpiredLeases 操作，并应用相应的 Agent 运行时状态变化。 */
    int recoverExpiredLeases(Instant now);
    
    /** 执行该 Agent 运行时组件中的 alreadySucceeded 操作。 */
    boolean alreadySucceeded(String executionKey);
    
    /** 通过 markRunSucceeded 操作更新持久化或内存中的运行状态。 */
    void markRunSucceeded(String runId,Instant now);
    
    /** 判断 isSideEffectAuthorized 对应的条件是否成立。 */
    boolean isSideEffectAuthorized(String taskId);
    
    /** 在 Agent 运行时边界间传递 ClaimedTask 数据的不可变值对象。 */
    record ClaimedTask(String taskId,String runId,String planId,int planVersion,String taskKey,String capabilityType,String inputJson,String inputHash,int attempt,String ownerUserId){}
}
