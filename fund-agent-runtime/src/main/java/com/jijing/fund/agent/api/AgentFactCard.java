package com.jijing.fund.agent.api;

import java.time.Instant;
import java.util.List;

/**
 * 由一次成功的工具结果整理出的短期记忆卡片，供后续检索替换。
 * 过期时刻由调用方解释；本类型不判断卡片是否仍然有效。
 */
public record AgentFactCard(
        String cardId,
        String conversationId,
        String runId,
        String toolName,
        String subjectKey,
        List<EvidenceReference> evidence,
        String dataJson,
        Instant createdAt,
        Instant expiresAt) {

    /**
     * 把空证据列表收成不可变空列表，避免后续遍历遇到空引用。
     * 不检查卡片或会话标识；列表中的空元素会保留，读取证据标识时才会失败。
     */
    public AgentFactCard {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * 按工具名把卡片归入固定记忆目录，供替换和按问句检索。
     * 工具名为空时按其他类处理；多个关键字同时命中时采用源码中的先后顺序，不会报错。
     */
    public String memoryCategory() {
        String name = toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
        if (name.contains("profile")) {
            return "PROFILE";
        }
        if (name.contains("realtime") || name.contains("quote")) {
            return "REALTIME";
        }
        if (name.contains("metric") || name.contains("comparison")) {
            return "METRICS";
        }
        if (name.contains("nav")) {
            return "NAV";
        }
        if (name.contains("holding") || name.contains("position")) {
            return "HOLDINGS";
        }
        if (name.contains("sector")
                || name.contains("event")
                || name.contains("catalyst")
                || name.contains("impact")
                || name.contains("industry")) {
            return "MARKET_SIGNALS";
        }
        if (name.contains("document") || name.contains("report")) {
            return "DOCUMENTS";
        }
        if (name.contains("personal") || name.contains("portfolio")) {
            return "USER_CONTEXT";
        }
        return "OTHER";
    }

    /**
     * 抽出证据标识列表，保持与证据引用相同的顺序。
     * 任一证据元素为空时抛出空指针异常，调用方必须保证列表元素完整。
     */
    public List<String> evidenceIds() {
        return evidence.stream().map(EvidenceReference::evidenceId).toList();
    }
}
