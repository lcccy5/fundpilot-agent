package com.jijing.fund.agent.api;

/** 在 Agent 运行时边界间传递 AgentTaskView 数据的不可变值对象。 */
public record AgentTaskView(String taskId,String taskKey,String capabilityType,String status,int attempts,String outputUri,String inputJson) {
    public AgentTaskView(String taskId,String taskKey,String capabilityType,String status,int attempts,String outputUri) {
        this(taskId, taskKey, capabilityType, status, attempts, outputUri, null);
    }
}
