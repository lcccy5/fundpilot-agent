package com.jijing.fund.agent.mcp;

import java.time.Instant;

/** 定义 McpCallAuditor 在 Agent 运行时中的能力契约。 */
public interface McpCallAuditor {
    McpCallAuditor NOOP=(connectionId,runId,capability,schemaHash,status,errorCode,now)->{};
    
    /** 通过 record 操作更新持久化或内存中的运行状态。 */
    void record(String connectionId,String runId,String capability,String schemaHash,String status,String errorCode,Instant now);
}
