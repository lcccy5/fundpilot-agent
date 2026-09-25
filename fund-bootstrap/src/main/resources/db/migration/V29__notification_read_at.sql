ALTER TABLE notification_record
    ADD COLUMN read_at DATETIME(3) NULL AFTER created_at;
