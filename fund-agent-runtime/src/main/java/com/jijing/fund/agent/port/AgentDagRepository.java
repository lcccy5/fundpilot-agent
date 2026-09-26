package com.jijing.fund.agent.port;

import com.jijing.fund.agent.api.AgentPlanView;
import com.jijing.fund.agent.api.AgentRunEventView;
import com.jijing.fund.agent.api.AgentRunView;
import com.jijing.fund.agent.planning.PlanDraft;
import com.jijing.fund.agent.routing.RouteDecision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 持久化计划运行的存储端口：会话、运行、计划、任务租约和审批。
 * 运行不存在或所有者不匹配时拒绝；过期或被取代的租约不得把任务写成失败。
 */
public interface AgentDagRepository {

    /**
     * 为所有者创建一个会话并返回标识。
     * 内存实现不持久化会话本身；所有者为空时仍可能返回新标识。
     */
    String createConversation(String ownerUserId, Instant now);

    /**
     * 开始一次运行并返回运行标识，初始状态为执行中。
     * 不校验会话是否已经存在；必填字段为空时仍会写入，后续按所有者读取会失败。
     */
    String startRun(
            String conversationId,
            String ownerUserId,
            String requestId,
            String executionMode,
            String routeReason,
            Instant now);

    /**
     * 记录本次运行采用的路由，并追加路由事件。
     * 运行不存在或所有者不匹配时拒绝，不写入事件。
     */
    void saveRoute(String runId, String ownerUserId, RouteDecision decision, Instant now);

    /**
     * 保存已通过校验的计划，并把无依赖任务标为可领取。
     * 运行不存在或所有者不匹配时拒绝；任务输入无法序列化时拒绝且不返回计划标识。
     */
    String saveValidatedPlan(String runId, String ownerUserId, PlanDraft draft, Instant now);

    /**
     * 按标识查找运行，不检查所有者。
     * 不存在时返回空，不抛出找不到运行的异常。
     */
    Optional<AgentRunView> findRun(String runId);

    /**
     * 读取调用方拥有的运行。
     * 运行不存在、所有者为空或不匹配时拒绝。
     */
    AgentRunView requireOwnedRun(String runId, String ownerUserId);

    /**
     * 读取调用方拥有的计划及其任务。
     * 运行不存在、所有者不匹配或尚未保存计划时拒绝。
     */
    AgentPlanView requireOwnedPlan(String runId, String ownerUserId);

    /**
     * 返回序号大于游标的事件。
     * 运行不存在或所有者不匹配时拒绝；没有更新的事件时返回空列表。
     */
    List<AgentRunEventView> eventsAfter(String runId, String ownerUserId, long lastSequence);

    /**
     * 给运行追加一条事件并推进序号。
     * 运行不存在时拒绝。此方法不检查所有者。
     */
    void appendEvent(String runId, String type, String payloadJson, Instant now);

    /**
     * 领取一条可执行任务并占用租约。
     * 没有可领取任务时返回空；租约短于 3 毫秒时拒绝。已取消的运行不会被领取。
     */
    Optional<ClaimedTask> claimReady(String workerId, Instant now, Duration lease);

    /**
     * 仅在当前未过期的租约上提交成功结果和幂等键。
     * 租约过期、工作者不匹配或代际不一致时抛出租约丢失，不得写成成功。
     */
    void completeTask(
            ClaimedTask claim,
            String executionKey,
            String outputUri,
            List<String> evidenceIds,
            Instant now);

    /**
     * 仅在当前未过期的租约上记录失败，并取消同运行中尚未成功的任务。
     * 租约已经失效时抛出租约丢失，避免把后继工作者的任务标失败。
     */
    void failTask(ClaimedTask claim, String reason, Instant now);

    /**
     * 把当前未过期租约上的任务改成等待审批，并释放租约。
     * 租约失效时拒绝；任务已经消失时直接返回。
     */
    void markWaitingApproval(ClaimedTask claim, String approvalId, Instant now);

    /**
     * 把尚未成功且未取消的任务改回可领取。
     * 任务不存在时静默返回，不创建新任务。
     */
    void markTaskReady(String taskId);

    /**
     * 取消运行，并取消其中尚未成功的任务。
     * 运行不存在或所有者不匹配时拒绝。
     */
    void cancelRun(String runId, String ownerUserId, Instant now);

    /**
     * 登记一条待核销的审批并返回审批标识。
     * 运行不存在或所有者不匹配时拒绝，不创建审批。
     */
    String requestApproval(
            String runId,
            String taskId,
            String ownerUserId,
            String actionType,
            String parameterHash,
            String summary,
            Instant expiresAt,
            Instant now);

    /**
     * 在未过期且参数摘要一致时核销审批，并把任务重新标为可领取。
     * 审批不存在、所有者不匹配、已使用、已过期或摘要不一致时返回 false，不改变任务。
     */
    boolean consumeApproval(String approvalId, String ownerUserId, String expectedHash, Instant now);

    /**
     * 拒绝审批，取消关联任务和运行。
     * 审批不存在或所有者不匹配时拒绝。
     */
    void rejectApproval(String approvalId, String ownerUserId, Instant now);

    /**
     * 把已到期仍处于执行中的任务恢复为可领取，并返回恢复数量。
     * 没有到期租约时返回 0；已经成功的任务不会被改回。
     */
    int recoverExpiredLeases(Instant now);

    /**
     * 判断同一幂等键是否已经成功执行过。
     * 未见过该键时返回 false，不抛异常。
     */
    boolean alreadySucceeded(String executionKey);

    /**
     * 把运行标成成功并追加完成事件。
     * 运行不存在时静默返回，不创建运行。
     */
    void markRunSucceeded(String runId, Instant now);

    /**
     * 判断任务是否已经获得副作用授权。
     * 任务不存在时返回 false。
     */
    boolean isSideEffectAuthorized(String taskId);

    /**
     * 仅延长当前未过期执行中租约的截止时间，不改变代际令牌。
     * 租约已丢失时返回 false；租约短于 3 毫秒时拒绝。
     */
    boolean renewLease(ClaimedTask claim, Instant now, Duration lease);

    /**
     * 在持有租约和存储锁时执行短写入。
     * 租约已丢失时拒绝且不执行写入；这里不能包住外部输入输出。
     */
    void withLease(ClaimedTask claim, Instant now, Runnable writes);

    /**
     * 一次已被工作者领取的任务，带有工作者和租约代际。
     * 兼容构造器不授予写入权；用它完成或失败任务时会被视为租约丢失。
     */
    record ClaimedTask(
            String taskId,
            String runId,
            String planId,
            int planVersion,
            String taskKey,
            String capabilityType,
            String inputJson,
            String inputHash,
            int attempt,
            String ownerUserId,
            String workerId,
            long leaseVersion) {

        /**
         * 给单独调用能力的测试保留的兼容构造。
         * 工作者为空且代际为 0，不能用来授权持久化写入。
         */
        public ClaimedTask(
                String taskId,
                String runId,
                String planId,
                int planVersion,
                String taskKey,
                String capabilityType,
                String inputJson,
                String inputHash,
                int attempt,
                String ownerUserId) {
            this(taskId, runId, planId, planVersion, taskKey, capabilityType, inputJson, inputHash, attempt,
                    ownerUserId, null, 0);
        }
    }
}
