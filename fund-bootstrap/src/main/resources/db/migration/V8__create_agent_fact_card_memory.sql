CREATE TABLE agent_fact_card (
    card_id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    subject_key VARCHAR(256) NULL,
    evidence_ids_json JSON NOT NULL,
    evidence_json JSON NOT NULL,
    data_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    PRIMARY KEY (card_id),
    KEY idx_fact_card_conversation_expiry (conversation_id, expires_at, created_at),
    KEY idx_fact_card_run (run_id),
    CONSTRAINT fk_fact_card_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(conversation_id),
    CONSTRAINT fk_fact_card_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_fact_card_usage (
    run_id CHAR(36) NOT NULL,
    card_id CHAR(36) NOT NULL,
    used_at DATETIME(3) NOT NULL,
    PRIMARY KEY (run_id, card_id),
    KEY idx_fact_card_usage_card (card_id, used_at),
    CONSTRAINT fk_fact_card_usage_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_fact_card_usage_card FOREIGN KEY (card_id) REFERENCES agent_fact_card(card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
