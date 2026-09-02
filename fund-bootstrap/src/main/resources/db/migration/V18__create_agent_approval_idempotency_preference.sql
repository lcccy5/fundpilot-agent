CREATE TABLE agent_approval (
    approval_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    task_id CHAR(36) NULL,
    owner_user_id CHAR(36) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    parameter_hash CHAR(64) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_by CHAR(36) NOT NULL,
    approved_by CHAR(36) NULL,
    expires_at DATETIME(3) NOT NULL,
    used_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (approval_id),
    KEY idx_approval_run (run_id, status),
    CONSTRAINT fk_approval_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_action_idempotency (
    idempotency_key VARCHAR(128) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    action_type VARCHAR(64) NOT NULL,
    parameter_hash CHAR(64) NOT NULL,
    result_ref VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (idempotency_key, owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_agent_preference (
    preference_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    preference_key VARCHAR(64) NOT NULL,
    preference_value VARCHAR(500) NOT NULL,
    source VARCHAR(32) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (preference_id),
    UNIQUE KEY uk_pref_owner_key (owner_user_id, preference_key),
    CONSTRAINT fk_pref_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
