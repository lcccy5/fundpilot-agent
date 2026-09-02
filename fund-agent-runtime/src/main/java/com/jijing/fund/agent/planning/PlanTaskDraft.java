package com.jijing.fund.agent.planning;

import java.util.List;
import java.util.Map;

/** 在 Agent 运行时边界间传递 PlanTaskDraft 数据的不可变值对象。 */
public record PlanTaskDraft(String taskKey,String taskType,Map<String,Object> input,List<String> dependencies,List<String> evidenceRequirement) {}
