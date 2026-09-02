CREATE TABLE IF NOT EXISTS fund_sync_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fund_code VARCHAR(6) NOT NULL,
    source_name VARCHAR(64) NOT NULL,
    sync_status VARCHAR(32) NOT NULL,
    synced_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_fund_sync_record_fund_code_synced_at (fund_code, synced_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='基金数据同步审计记录';

