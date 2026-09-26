package com.jijing.fund.infrastructure.mcp;

import com.jijing.fund.agent.mcp.McpCallAuditor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 把 MCP 调用写进审计表。连接编号为空时使用内置只读连接。
 * 连接行用 {@code INSERT IGNORE}，重复确保不会覆盖已有端点。
 * 每次审计都生成新的调用编号，因此重复调用会留下多行，而不是去重。
 * 没有 HTTP 超时；数据库失败直接抛出。
 */
@Repository
public class JdbcMcpCallAuditor implements McpCallAuditor {
    /** 未指定外部连接时使用的占位连接。 */
    static final String INTERNAL_CONNECTION = "00000000-0000-0000-0000-000000000023";
    private final JdbcTemplate jdbc;

    /** 不在构造时插入占位连接。 */
    public JdbcMcpCallAuditor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 先保证连接行存在，再写一条调用。错误码可以为空。 */
    @Override
    public void record(String connectionId, String runId, String capability, String schemaHash, String status,
            String errorCode, Instant now) {
        String connection = connectionId == null || connectionId.isBlank() ? INTERNAL_CONNECTION : connectionId;
        ensureConnection(connection, now);
        jdbc.update("""
                INSERT INTO mcp_call_audit(call_id,connection_id,run_id,capability_name,schema_hash,status,error_code,called_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), connection, runId, capability, schemaHash, status, errorCode,
                Timestamp.from(now));
    }

    /** 已存在的连接保持原样，包括名称和密钥引用。 */
    private void ensureConnection(String connectionId, Instant now) {
        jdbc.update("""
                INSERT IGNORE INTO mcp_connection(connection_id,owner_user_id,display_name,endpoint_ref,secret_reference,status,created_at)
                VALUES(?,NULL,'internal-readonly','local://fund-mcp','none','ACTIVE',?)
                """, connectionId, Timestamp.from(now));
    }
}
