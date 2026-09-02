CREATE TABLE data_provider_status (
    provider_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    last_success_at DATETIME(3) NULL,
    last_failure_at DATETIME(3) NULL,
    consecutive_failures INT NOT NULL DEFAULT 0,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE data_quality_issue (
    issue_id CHAR(36) NOT NULL,
    provider_id VARCHAR(64) NOT NULL,
    data_kind VARCHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    safe_message VARCHAR(500) NOT NULL,
    occurrence_count INT NOT NULL DEFAULT 1,
    first_seen_at DATETIME(3) NOT NULL,
    last_seen_at DATETIME(3) NOT NULL,
    PRIMARY KEY (issue_id),
    UNIQUE KEY uk_quality_issue_fingerprint (provider_id, fingerprint),
    KEY idx_quality_issue_recent (last_seen_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

