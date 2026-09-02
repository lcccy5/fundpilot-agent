CREATE TABLE agent_role_assignment (
    assignment_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    role_name VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (assignment_id),
    UNIQUE KEY uk_role_task (plan_id, task_key),
    CONSTRAINT fk_role_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_role_execution (
    execution_id CHAR(36) NOT NULL,
    assignment_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_artifact_review (
    review_id CHAR(36) NOT NULL,
    artifact_id CHAR(36) NOT NULL,
    reviewer_role VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    findings_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_verification_finding (
    finding_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    code VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    detail VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (finding_id),
    KEY idx_finding_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
