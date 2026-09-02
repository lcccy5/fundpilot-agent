CREATE TABLE mcp_connection (
    connection_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NULL,
    display_name VARCHAR(128) NOT NULL,
    endpoint_ref VARCHAR(500) NOT NULL,
    secret_reference VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (connection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mcp_capability_snapshot (
    snapshot_id CHAR(36) NOT NULL,
    connection_id CHAR(36) NOT NULL,
    capability_name VARCHAR(128) NOT NULL,
    schema_hash CHAR(64) NOT NULL,
    permission_json JSON NOT NULL,
    captured_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_mcp_cap (connection_id, capability_name, schema_hash),
    CONSTRAINT fk_mcp_snap_conn FOREIGN KEY (connection_id) REFERENCES mcp_connection(connection_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE mcp_call_audit (
    call_id CHAR(36) NOT NULL,
    connection_id CHAR(36) NOT NULL,
    run_id CHAR(36) NULL,
    capability_name VARCHAR(128) NOT NULL,
    schema_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    called_at DATETIME(3) NOT NULL,
    PRIMARY KEY (call_id),
    KEY idx_mcp_call_conn (connection_id, called_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
