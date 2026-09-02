CREATE TABLE notification_rule (
    rule_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    rule_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    threshold_json JSON NOT NULL,
    comparison VARCHAR(16) NOT NULL,
    cooldown_seconds INT NOT NULL,
    quiet_hours_json JSON NULL,
    channel VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL,
    version BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (rule_id),
    KEY idx_rule_owner (owner_user_id, enabled),
    CONSTRAINT fk_rule_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_record (
    notification_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    rule_id CHAR(36) NOT NULL,
    trigger_fingerprint VARCHAR(128) NOT NULL,
    event_id CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    quiet_held TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (notification_id),
    UNIQUE KEY uk_notify_dedup (owner_user_id, rule_id, trigger_fingerprint),
    CONSTRAINT fk_notify_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE notification_delivery_attempt (
    attempt_id CHAR(36) NOT NULL,
    notification_id CHAR(36) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempted_at DATETIME(3) NOT NULL,
    error_code VARCHAR(64) NULL,
    PRIMARY KEY (attempt_id),
    KEY idx_delivery_notification (notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_notification_preference (
    owner_user_id CHAR(36) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    enabled TINYINT NOT NULL,
    quiet_hours_json JSON NULL,
    version BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (owner_user_id, channel),
    CONSTRAINT fk_pref_notify_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
