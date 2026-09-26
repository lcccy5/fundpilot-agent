package com.jijing.fund.agent.mcp;

import java.util.Objects;

/**
 * 决定 MCP 计划是否因模式变化暂停，以及外部正文能否进入后续步骤。
 * 本类只返回布尔值；暂停或拒绝由调用方抛出异常。
 */
public final class McpGovernance {
    /**
     * 已保存的模式摘要与当前摘要不一致，或任一摘要缺失时，计划必须暂停。
     * 两者都存在且相等时返回 false，允许继续。
     */
    public boolean shouldPausePlan(String storedSchemaHash, String liveSchemaHash) {
        return storedSchemaHash == null || liveSchemaHash == null || !Objects.equals(storedSchemaHash, liveSchemaHash);
    }

    /**
     * 拒绝空正文，以及试图覆盖指令、冒充系统提示或伪造证据编号的正文。
     * 其余正文返回 true，但不因此把外部内容标成可信证据。
     */
    public boolean acceptExternalContent(String content) {
        if (content == null) {
            return false;
        }
        String lower = content.toLowerCase();
        return !(lower.contains("ignore previous") || lower.contains("system prompt") || lower.contains("evidence-id:forged"));
    }
}
