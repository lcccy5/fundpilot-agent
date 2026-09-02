ALTER TABLE fund_sync_record
    ADD COLUMN sync_type VARCHAR(32) NOT NULL DEFAULT 'FULL' AFTER source_name,
    ADD COLUMN date_range_start DATE NULL AFTER sync_type,
    ADD COLUMN date_range_end DATE NULL AFTER date_range_start,
    ADD COLUMN requested_count INT NOT NULL DEFAULT 0 AFTER date_range_end,
    ADD COLUMN saved_count INT NOT NULL DEFAULT 0 AFTER requested_count,
    ADD COLUMN error_code VARCHAR(64) NULL AFTER sync_status,
    ADD COLUMN error_message VARCHAR(500) NULL AFTER error_code,
    ADD COLUMN started_at DATETIME(3) NULL AFTER error_message,
    ADD COLUMN finished_at DATETIME(3) NULL AFTER started_at;

