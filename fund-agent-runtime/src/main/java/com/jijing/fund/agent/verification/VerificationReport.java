package com.jijing.fund.agent.verification;

import java.util.List;

/** 在 Agent 运行时边界间传递 VerificationReport 数据的不可变值对象。 */
public record VerificationReport(boolean passed,List<String> findings,List<String> evidenceIds) {}
