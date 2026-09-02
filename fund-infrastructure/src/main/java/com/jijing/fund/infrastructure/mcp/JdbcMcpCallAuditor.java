package com.jijing.fund.infrastructure.mcp;

import com.jijing.fund.agent.mcp.McpCallAuditor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMcpCallAuditor implements McpCallAuditor {
    static final String INTERNAL_CONNECTION="00000000-0000-0000-0000-000000000023";
    private final JdbcTemplate jdbc;
    public JdbcMcpCallAuditor(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Override public void record(String connectionId,String runId,String capability,String schemaHash,String status,String errorCode,Instant now){
        String conn=connectionId==null||connectionId.isBlank()?INTERNAL_CONNECTION:connectionId;
        ensureConnection(conn,now);
        jdbc.update("""
                INSERT INTO mcp_call_audit(call_id,connection_id,run_id,capability_name,schema_hash,status,error_code,called_at)
                VALUES(?,?,?,?,?,?,?,?)
                """,UUID.randomUUID().toString(),conn,runId,capability,schemaHash,status,errorCode,Timestamp.from(now));
    }
    private void ensureConnection(String connectionId,Instant now){
        jdbc.update("""
                INSERT IGNORE INTO mcp_connection(connection_id,owner_user_id,display_name,endpoint_ref,secret_reference,status,created_at)
                VALUES(?,NULL,'internal-readonly','local://fund-mcp','none','ACTIVE',?)
                """,connectionId,Timestamp.from(now));
    }
}
