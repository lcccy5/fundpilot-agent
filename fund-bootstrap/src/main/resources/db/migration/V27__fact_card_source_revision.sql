ALTER TABLE agent_fact_card
    ADD COLUMN source_revision VARCHAR(128) NULL AFTER data_json,
    ADD COLUMN content_hash CHAR(64) NULL AFTER source_revision,
    ADD COLUMN supersedes_card_id CHAR(36) NULL AFTER content_hash;
