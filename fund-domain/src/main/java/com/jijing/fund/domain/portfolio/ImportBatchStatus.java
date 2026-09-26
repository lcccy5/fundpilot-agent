package com.jijing.fund.domain.portfolio;

/**
 * 导入批次的生命周期状态，按名称持久化：PREVIEWED 为已解析待确认，COMMITTED 为已写入交易流水，
 * DELETED 为已放弃（导入行被删除）。未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum ImportBatchStatus { PREVIEWED, COMMITTED, DELETED }
