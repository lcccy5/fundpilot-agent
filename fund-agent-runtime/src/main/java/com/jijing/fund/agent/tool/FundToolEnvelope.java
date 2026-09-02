package com.jijing.fund.agent.tool;

import com.jijing.fund.agent.api.EvidenceReference;
import java.util.List;

/** 在 Agent 运行时边界间传递 FundToolEnvelope 数据的不可变值对象。 */
public record FundToolEnvelope<T>(String toolName, String toolVersion, ToolResultStatus status,
        T data, List<EvidenceReference> evidence, List<String> warnings,
        String errorCode, String safeErrorMessage) {
    public FundToolEnvelope {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
    
    /** 执行该 Agent 运行时组件中的 success 操作。 */
    public static <T> FundToolEnvelope<T> success(String name, T data, EvidenceReference evidence, List<String> warnings) {
        return new FundToolEnvelope<>(name, "fund-tools-v1", ToolResultStatus.SUCCESS, data,
                evidence == null ? List.of() : List.of(evidence), warnings, null, null);
    }
}
