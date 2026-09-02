package com.jijing.fund.agent.execution;

import java.util.List;

/** 在 Agent 运行时边界间传递 Observation 数据的不可变值对象。 */
public record Observation(String status,String summary,List<String> evidenceIds,List<String> warnings,List<String> missingFields,String freshness,List<String> nextAllowedActions) {}
