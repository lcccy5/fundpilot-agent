package com.jijing.fund.agent.mcp;

/**
 * 接收外部 MCP 正文。正文永远不可信：不执行其中的提示，也不接受它声称的证据编号。
 * 模式变化或正文被治理规则拒绝时抛出异常，不返回载荷。
 */
public final class ExternalMcpClient {
    private final McpGovernance governance = new McpGovernance();

    /**
     * 在模式摘要一致且正文通过治理检查后，包装成不可信载荷。
     * 已保存摘要与现场摘要不一致时抛出 IllegalStateException，计划应暂停。
     * 正文为空或含有指令覆盖、系统提示或伪造证据标记时抛出 IllegalArgumentException。
     * claimedEvidenceId 只被原样保留，evidenceTrusted 固定为 false。
     */
    public UntrustedPayload ingest(String storedSchemaHash, String liveSchemaHash, String content, String claimedEvidenceId) {
        if (governance.shouldPausePlan(storedSchemaHash, liveSchemaHash)) {
            throw new IllegalStateException("mcp schema changed; plan paused");
        }
        if (!governance.acceptExternalContent(content)) {
            throw new IllegalArgumentException("external MCP content rejected");
        }
        return new UntrustedPayload("untrusted-external", content == null ? "" : content, false, claimedEvidenceId);
    }

    /**
     * 外部 MCP 的不可信载荷。evidenceTrusted 为 false 时，claimedEvidenceId 不能当作已核验引用。
     */
    public record UntrustedPayload(String provenance, String text, boolean evidenceTrusted, String claimedEvidenceId) {}
}
