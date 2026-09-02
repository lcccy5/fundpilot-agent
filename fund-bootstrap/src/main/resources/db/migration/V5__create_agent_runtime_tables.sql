CREATE TABLE agent_conversation (
    conversation_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NULL,
    PRIMARY KEY (conversation_id),
    KEY idx_conversation_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_message (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content MEDIUMTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    token_count INT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_message_conversation (conversation_id, id),
    CONSTRAINT fk_message_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_run (
    run_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    tool_schema_version VARCHAR(64) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    model_rounds INT NOT NULL DEFAULT 0,
    tool_call_count INT NOT NULL DEFAULT 0,
    prompt_tokens INT NULL,
    completion_tokens INT NULL,
    total_tokens INT NULL,
    error_code VARCHAR(64) NULL,
    safe_error_message VARCHAR(500) NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    PRIMARY KEY (run_id),
    KEY idx_run_conversation (conversation_id, started_at),
    KEY idx_run_request (request_id),
    CONSTRAINT fk_run_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_tool_call (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id CHAR(36) NOT NULL,
    tool_call_id VARCHAR(128) NULL,
    tool_name VARCHAR(128) NOT NULL,
    tool_version VARCHAR(32) NOT NULL,
    argument_hash CHAR(64) NOT NULL,
    arguments_redacted_json JSON NULL,
    result_status VARCHAR(32) NOT NULL,
    evidence_ids_json JSON NULL,
    error_code VARCHAR(64) NULL,
    duration_ms BIGINT NOT NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_tool_call_run (run_id, id),
    KEY idx_tool_call_name (tool_name, started_at),
    CONSTRAINT fk_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
