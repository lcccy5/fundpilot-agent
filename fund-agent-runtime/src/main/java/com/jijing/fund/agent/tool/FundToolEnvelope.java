package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import java.util.List;

/**
 * 工具返回给模型的统一信封。成功时携带数据和证据；失败时 data 为空，并用 errorCode 说明原因。
 * 缺少引用不会在这里抛错，只表现为 evidence 为空列表。
 */
public record FundToolEnvelope<T>(String toolName, String toolVersion, ToolResultStatus status,
        T data, List<EvidenceReference> evidence, List<String> warnings,
        String errorCode, String safeErrorMessage) {
    /**
     * 把空的证据和告警收成不可变空列表，避免调用方收到 null。
     * 不校验 toolName 或 status；错误码和安全文案允许为空，表示没有失败。
     */
    public FundToolEnvelope {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 组装 fund-tools-v1 成功信封。evidence 为空时不伪造引用，只记录空列表。
     */
    public static <T> FundToolEnvelope<T> success(String name, T data, EvidenceReference evidence, List<String> warnings) {
        return new FundToolEnvelope<>(name, "fund-tools-v1", ToolResultStatus.SUCCESS, data,
                evidence == null ? List.of() : List.of(evidence), warnings, null, null);
    }
}
