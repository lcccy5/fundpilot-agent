ALTER TABLE agent_run
    ADD COLUMN research_message VARCHAR(500) NULL AFTER route_reason,
    ADD COLUMN subject_fund VARCHAR(32) NULL AFTER research_message;

ALTER TABLE agent_run
    ADD KEY idx_run_started (started_at);
