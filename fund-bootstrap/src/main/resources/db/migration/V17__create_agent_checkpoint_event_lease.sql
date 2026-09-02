CREATE TABLE agent_checkpoint (
    checkpoint_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    plan_id CHAR(36) NULL,
    sequence BIGINT NOT NULL,
    completed_task_keys_json JSON NOT NULL,
    state_ref VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (checkpoint_id),
    UNIQUE KEY uk_checkpoint_run_seq (run_id, sequence),
    CONSTRAINT fk_checkpoint_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_event (
    event_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (event_id),
    UNIQUE KEY uk_event_run_seq (run_id, sequence),
    CONSTRAINT fk_event_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE agent_task_lease (
    task_id CHAR(36) NOT NULL,
    owner_instance VARCHAR(128) NOT NULL,
    lease_until DATETIME(3) NOT NULL,
    heartbeat_at DATETIME(3) NOT NULL,
    attempt INT NOT NULL,
    version BIGINT NOT NULL,
    PRIMARY KEY (task_id),
    CONSTRAINT fk_lease_task FOREIGN KEY (task_id) REFERENCES agent_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
