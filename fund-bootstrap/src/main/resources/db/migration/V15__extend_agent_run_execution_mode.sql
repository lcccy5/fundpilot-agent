-- V4: persist execution mode on existing runs. Legacy rows become LEGACY_TOOL_AGENT.
ALTER TABLE agent_run
    ADD COLUMN execution_mode VARCHAR(32) NOT NULL DEFAULT 'LEGACY_TOOL_AGENT' AFTER status,
    ADD COLUMN router_version VARCHAR(64) NULL AFTER execution_mode,
    ADD COLUMN route_reason VARCHAR(128) NULL AFTER router_version,
    ADD COLUMN budget_json JSON NULL AFTER route_reason,
    ADD COLUMN deadline_at DATETIME(3) NULL AFTER budget_json,
    ADD COLUMN parent_run_id CHAR(36) NULL AFTER deadline_at,
    ADD COLUMN last_event_sequence BIGINT NOT NULL DEFAULT 0 AFTER parent_run_id;

CREATE TABLE agent_route_decision (
    decision_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NULL,
    execution_mode VARCHAR(32) NOT NULL,
    direct_variant VARCHAR(32) NULL,
    router_version VARCHAR(64) NOT NULL,
    features_json JSON NOT NULL,
    matched_rule VARCHAR(64) NOT NULL,
    model_suggestion VARCHAR(32) NULL,
    override_reason VARCHAR(128) NULL,
    estimated_budget_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (decision_id),
    UNIQUE KEY uk_route_run (run_id),
    KEY idx_route_owner_time (owner_user_id, created_at),
    CONSTRAINT fk_route_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
