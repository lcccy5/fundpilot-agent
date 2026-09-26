package com.jijing.fund.agent.verification;

import java.util.List;

/**
 * 运行核对的结果。passed 为 false 时 findings 说明未完成任务或缺少核对步骤；
 * evidenceIds 为空表示没有可追溯引用，但本类型不因此拒绝构造。
 */
public record VerificationReport(boolean passed, List<String> findings, List<String> evidenceIds) {}
