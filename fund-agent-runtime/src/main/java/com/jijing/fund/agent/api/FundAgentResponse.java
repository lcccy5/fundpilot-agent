package com.jijing.fund.agent.api;

import java.time.Instant;
import java.util.List;

/**
 * 一次对话完成后返回给调用方的答案、证据、限制和用量。
 * 完成时刻和模型信息可以为空，表示生产者没有记录；本类型不补默认值。
 */
public record FundAgentResponse(
        String conversationId,
        String runId,
        String answer,
        List<EvidenceReference> evidence,
        List<String> limitations,
        String promptVersion,
        String modelProvider,
        String modelName,
        TokenUsage usage,
        Instant completedAt) {

    /**
     * 把空的证据和限制列表收成不可变副本。
     * 不校验答案或运行标识；空列表表示没有证据或没有已知限制，而不是失败。
     */
    public FundAgentResponse {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
