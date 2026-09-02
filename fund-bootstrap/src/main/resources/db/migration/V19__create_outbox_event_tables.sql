CREATE TABLE outbox_event (
    event_id CHAR(36) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    owner_user_id CHAR(36) NULL,
    schema_version VARCHAR(32) NOT NULL,
    payload_json JSON NOT NULL,
    evidence_ids_json JSON NULL,
    deduplication_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(3) NULL,
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(3) NULL,
    occurred_at DATETIME(3) NOT NULL,
    published_at DATETIME(3) NULL,
    last_error_code VARCHAR(64) NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_outbox_dedup (deduplication_key),
    KEY idx_outbox_status_next (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_consumption (
    consumer_name VARCHAR(64) NOT NULL,
    event_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    consumed_at DATETIME(3) NOT NULL,
    PRIMARY KEY (consumer_name, event_id),
    CONSTRAINT fk_consumption_event FOREIGN KEY (event_id) REFERENCES outbox_event(event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE event_dead_letter (
    dead_letter_id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    detail VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (dead_letter_id),
    KEY idx_dlq_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
