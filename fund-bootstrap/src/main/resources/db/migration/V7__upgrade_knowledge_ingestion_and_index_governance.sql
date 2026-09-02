ALTER TABLE knowledge_ingestion_job
    ADD COLUMN last_completed_step VARCHAR(32) NULL,
    ADD COLUMN lease_owner VARCHAR(128) NULL,
    ADD COLUMN lease_until DATETIME(3) NULL,
    ADD COLUMN next_retry_at DATETIME(3) NULL,
    ADD COLUMN embedded_batch_no INT NOT NULL DEFAULT 0,
    ADD COLUMN indexed_chunk_count INT NOT NULL DEFAULT 0,
    ADD KEY idx_ingestion_claim (status, next_retry_at, lease_until);

CREATE TABLE knowledge_document_chunk_metadata (
    chunk_id VARCHAR(64) NOT NULL,
    version_id CHAR(36) NOT NULL,
    chunk_order INT NOT NULL,
    page_start INT NOT NULL,
    page_end INT NOT NULL,
    heading_path VARCHAR(1000) NULL,
    token_count INT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    artifact_storage_key VARCHAR(1000) NOT NULL,
    embedding_status VARCHAR(32) NOT NULL,
    indexed_status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (chunk_id),
    UNIQUE KEY uk_chunk_version_order (version_id, chunk_order),
    KEY idx_chunk_version_status (version_id, embedding_status, indexed_status),
    CONSTRAINT fk_chunk_version FOREIGN KEY (version_id)
        REFERENCES knowledge_document_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_index_rebuild (
    rebuild_id CHAR(36) NOT NULL,
    source_alias VARCHAR(256) NOT NULL,
    previous_index VARCHAR(256) NULL,
    target_index VARCHAR(256) NOT NULL,
    embedding_version VARCHAR(128) NOT NULL,
    chunking_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_chunk_count BIGINT NULL,
    indexed_chunk_count BIGINT NOT NULL DEFAULT 0,
    validation_report_json JSON NULL,
    error_code VARCHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (rebuild_id),
    KEY idx_rebuild_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
