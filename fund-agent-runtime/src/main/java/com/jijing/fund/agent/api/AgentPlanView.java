package com.jijing.fund.agent.api;

import java.util.List;

/** 在 Agent 运行时边界间传递 AgentPlanView 数据的不可变值对象。 */
public record AgentPlanView(String planId,String runId,String ownerUserId,String status,String goal,int planVersion,List<AgentTaskView> tasks) {}
