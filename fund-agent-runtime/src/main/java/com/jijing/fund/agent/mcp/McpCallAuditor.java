package com.jijing.fund.agent.mcp;

import java.time.Instant;

/**
 * 记录 MCP 调用的成功或拒绝。实现失败时应抛出异常，让调用方不能把未审计的工具结果当成成功。
 */
public interface McpCallAuditor {
    /**
     * 不写任何审计的空实现。调用不会失败，也不能用来证明调用已被记录。
     */
    McpCallAuditor NOOP = (connectionId, runId, capability, schemaHash, status, errorCode, now) -> {};

    /**
     * 记录一次 MCP 调用。status 为 REJECTED 时 errorCode 说明拒绝原因；成功时 errorCode 可为空。
     * 连接或运行标识可为空，表示调用发生在尚未绑定运行的入口。
     */
    void record(String connectionId, String runId, String capability, String schemaHash, String status, String errorCode, Instant now);
}
