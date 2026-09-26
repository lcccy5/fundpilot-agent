package com.jijing.fund.agent.api;

import java.util.List;

/**
 * 持久化计划运行的应用入口：提交、查询、取消、审批和租约恢复。
 * 运行不存在或不属于该所有者时，实现应拒绝访问而不是返回空快照。
 */
public interface AgentRunUseCase {

    /**
     * 按已审计的路由创建一次计划运行并保存校验后的计划。
     * 命令、消息或所有者为空时拒绝；路由不是计划执行时也拒绝，且不留下运行记录。
     */
    AgentRunView submit(AgentRunCommand command);

    /**
     * 读取调用方拥有的运行快照。
     * 运行不存在或所有者不匹配时拒绝，不区分这两种情况。
     */
    AgentRunView get(String runId, String ownerUserId);

    /**
     * 读取该运行已保存的计划及任务。
     * 运行不存在、所有者不匹配或计划尚未写入时拒绝。
     */
    AgentPlanView plan(String runId, String ownerUserId);

    /**
     * 返回序号严格大于给定游标的事件。
     * 游标为空时从序号 0 之后开始；运行不存在或所有者不匹配时拒绝。
     */
    List<AgentRunEventView> events(String runId, String ownerUserId, Long lastEventId);

    /**
     * 取消运行，并取消尚未成功的任务。
     * 运行不存在或所有者不匹配时拒绝；已经成功的任务保持成功。
     */
    void cancel(String runId, String ownerUserId);

    /**
     * 按当前参数摘要核销审批，并继续拉取可执行任务。
     * 运行不存在、审批无效或参数已变化时拒绝，不会把任务标成已授权。
     */
    void approve(String runId, String approvalId, String ownerUserId, String currentParameters);

    /**
     * 拒绝审批，并取消对应运行。
     * 运行或审批不存在、或不属于该所有者时拒绝。
     */
    void reject(String runId, String approvalId, String ownerUserId);

    /**
     * 回收已经到期的任务租约，让任务重新可被领取。
     * 没有到期租约时返回 0；不删除已经成功的任务。
     */
    int recover(java.time.Instant now);
}
