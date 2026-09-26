package com.jijing.fund.domain.risk;

/**
 * 风险测评得出的风险承受等级，从低到高依次为保守、稳健、成长、激进；声明顺序即等级高低，不能随意调整。
 * 按名称持久化，未知名称在 {@code valueOf} 时抛出 IllegalArgumentException。
 */
public enum RiskLevel { CONSERVATIVE, BALANCED, GROWTH, AGGRESSIVE }
