package com.jijing.fund.agent.api;

import java.util.List;

/** 定义 AgentRunUseCase 在 Agent 运行时中的能力契约。 */
public interface AgentRunUseCase {
    
    /** 创建并初始化当前 Agent 操作所需的 submit 结果。 */
    AgentRunView submit(AgentRunCommand command);
    
    /** 获取当前 Agent 操作所需的 get 结果。 */
    AgentRunView get(String runId,String ownerUserId);
    
    /** 获取当前 Agent 操作所需的 plan 结果。 */
    AgentPlanView plan(String runId,String ownerUserId);
    
    /** 获取当前 Agent 操作所需的 events 结果。 */
    List<AgentRunEventView> events(String runId,String ownerUserId,Long lastEventId);
    
    /** Applies the cancel state transition to the relevant agent resource. */
    void cancel(String runId,String ownerUserId);
    
    /** Applies the approve state transition to the relevant agent resource. */
    void approve(String runId,String approvalId,String ownerUserId,String currentParameters);
    
    /** Applies the reject state transition to the relevant agent resource. */
    void reject(String runId,String approvalId,String ownerUserId);
    
    /** 执行 recover 操作，并应用相应的 Agent 运行时状态变化。 */
    int recover(java.time.Instant now);
}
