package com.jijing.fund.domain.portfolio;

/**
 * 投资组合状态，按名称持久化：只有 ACTIVE 组合允许录入交易，ARCHIVED 组合只读。
 * 未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum PortfolioStatus { ACTIVE, ARCHIVED }
