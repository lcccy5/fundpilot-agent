CREATE TABLE agent_plan (
    plan_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    goal VARCHAR(500) NOT NULL,
    input_snapshot_json JSON NOT NULL,
    budget_json JSON NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (plan_id),
    UNIQUE KEY uk_plan_run (run_id),
    KEY idx_plan_owner (owner_user_id, status),
    CONSTRAINT fk_plan_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_plan_version (
    plan_id CHAR(36) NOT NULL,
    plan_version INT NOT NULL,
    draft_json JSON NOT NULL,
    diff_json JSON NULL,
    replan_reason VARCHAR(128) NULL,
    validated_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (plan_id, plan_version),
    CONSTRAINT fk_plan_version_plan FOREIGN KEY (plan_id) REFERENCES agent_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_task (
    task_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    plan_version INT NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    capability_type VARCHAR(64) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    input_json JSON NOT NULL,
    input_hash CHAR(64) NOT NULL,
    output_uri VARCHAR(500) NULL,
    output_hash CHAR(64) NULL,
    evidence_ids_json JSON NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(3) NULL,
    completed_at DATETIME(3) NULL,
    duration_ms BIGINT NULL,
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_plan_task_key (plan_id, task_key),
    KEY idx_task_status (plan_id, status),
    CONSTRAINT fk_task_plan FOREIGN KEY (plan_id) REFERENCES agent_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_task_dependency (
    plan_id CHAR(36) NOT NULL,
    task_key VARCHAR(64) NOT NULL,
    depends_on_task_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (plan_id, task_key, depends_on_task_key),
    CONSTRAINT chk_task_not_self CHECK (task_key <> depends_on_task_key),
    CONSTRAINT fk_dep_plan FOREIGN KEY (plan_id) REFERENCES agent_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_task_attempt (
    attempt_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (attempt_id),
    UNIQUE KEY uk_task_attempt (task_id, attempt_no),
    CONSTRAINT fk_attempt_task FOREIGN KEY (task_id) REFERENCES agent_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_artifact (
    artifact_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NOT NULL,
    task_id CHAR(36) NULL,
    owner_user_id CHAR(36) NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    content_uri VARCHAR(500) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    summary VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (artifact_id),
    KEY idx_artifact_plan (plan_id),
    CONSTRAINT fk_artifact_plan FOREIGN KEY (plan_id) REFERENCES agent_plan(plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
