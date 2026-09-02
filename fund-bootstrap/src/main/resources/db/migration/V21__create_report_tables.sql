CREATE TABLE report_schedule (
    schedule_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    cadence VARCHAR(32) NOT NULL,
    plan_template VARCHAR(64) NOT NULL,
    enabled TINYINT NOT NULL,
    next_run_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (schedule_id),
    KEY idx_schedule_next (enabled, next_run_at),
    CONSTRAINT fk_schedule_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_job (
    job_id CHAR(36) NOT NULL,
    schedule_id CHAR(36) NULL,
    owner_user_id CHAR(36) NOT NULL,
    run_id CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (job_id),
    KEY idx_job_owner (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_artifact (
    artifact_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    content_uri VARCHAR(500) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    media_type VARCHAR(128) NOT NULL,
    evidence_manifest_json JSON NOT NULL,
    data_cutoff DATETIME(3) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    model_version VARCHAR(128) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (artifact_id),
    KEY idx_report_artifact_job (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_version (
    job_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    artifact_id CHAR(36) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (job_id, version_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE report_publication (
    publication_id CHAR(36) NOT NULL,
    job_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    published_at DATETIME(3) NULL,
    PRIMARY KEY (publication_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
