CREATE TABLE knowledge_document (
    document_id CHAR(36) NOT NULL,
    external_document_id VARCHAR(256) NULL,
    title VARCHAR(500) NOT NULL,
    document_type VARCHAR(64) NOT NULL,
    publisher VARCHAR(256) NULL,
    source_name VARCHAR(128) NOT NULL,
    source_uri VARCHAR(2000) NULL,
    published_date DATE NULL,
    active_version_id CHAR(36) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (document_id),
    KEY idx_document_external (source_name, external_document_id),
    KEY idx_document_type_date (document_type, published_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_document_version (
    version_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    version_no INT NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    storage_key VARCHAR(1000) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    page_count INT NULL,
    text_char_count BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    parser_version VARCHAR(64) NULL,
    chunking_version VARCHAR(64) NULL,
    embedding_version VARCHAR(128) NULL,
    index_name VARCHAR(256) NULL,
    warnings_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    ready_at DATETIME(3) NULL,
    PRIMARY KEY (version_id),
    UNIQUE KEY uk_document_version_no (document_id, version_no),
    UNIQUE KEY uk_document_content (document_id, content_sha256),
    CONSTRAINT fk_version_document FOREIGN KEY (document_id) REFERENCES knowledge_document(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_document_fund (
    document_id CHAR(36) NOT NULL,
    fund_code CHAR(6) NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    PRIMARY KEY (document_id, fund_code),
    KEY idx_document_fund_code (fund_code, document_id),
    CONSTRAINT fk_document_fund_document FOREIGN KEY (document_id) REFERENCES knowledge_document(document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE knowledge_ingestion_job (
    job_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    chunk_count INT NULL,
    error_code VARCHAR(64) NULL,
    safe_error_message VARCHAR(500) NULL,
    started_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (job_id),
    KEY idx_ingestion_status (status, updated_at),
    CONSTRAINT fk_ingestion_version FOREIGN KEY (version_id) REFERENCES knowledge_document_version(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
