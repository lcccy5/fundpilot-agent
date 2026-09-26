package com.jijing.fund.agent.orchestration;

import java.util.List;

/**
 * 从回答中切出的一条声明及其引用。
 * 证据列表为 null 时保存为空列表，后续兼容性检查会因缺少证据而失败。
 * 本对象不执行计划或路由；对等代理产物失败不在这里被转换成已引用声明。
 */
public record AnswerClaim(String claimId, ClaimType type, String text, List<String> evidenceIds) {

    /**
     * 复制证据编号，避免调用方事后修改列表。
     * 证据为 null 时使用空列表，声明会被视为尚未引用。
     */
    public AnswerClaim {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }
}
