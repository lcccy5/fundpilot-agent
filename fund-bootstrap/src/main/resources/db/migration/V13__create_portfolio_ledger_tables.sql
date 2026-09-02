CREATE TABLE user_portfolio (
    portfolio_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NULL,
    PRIMARY KEY (portfolio_id),
    KEY idx_portfolio_owner_status (owner_user_id, status, updated_at),
    CONSTRAINT fk_portfolio_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio_import_batch (
    batch_id CHAR(36) NOT NULL,
    portfolio_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    invalid_rows INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    committed_at DATETIME(3) NULL,
    PRIMARY KEY (batch_id),
    KEY idx_import_owner_created (owner_user_id, created_at),
    CONSTRAINT fk_import_portfolio FOREIGN KEY (portfolio_id) REFERENCES user_portfolio(portfolio_id),
    CONSTRAINT fk_import_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio_import_row (
    batch_id CHAR(36) NOT NULL,
    source_row_number INT NOT NULL,
    raw_json JSON NOT NULL,
    error_code VARCHAR(64) NULL,
    safe_message VARCHAR(500) NULL,
    PRIMARY KEY (batch_id, source_row_number),
    CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id) REFERENCES portfolio_import_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fund_transaction (
    transaction_id CHAR(36) NOT NULL,
    portfolio_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    fund_code CHAR(6) NOT NULL,
    transaction_type VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    confirm_date DATE NOT NULL,
    shares DECIMAL(24,8) NOT NULL DEFAULT 0,
    gross_amount DECIMAL(24,4) NOT NULL DEFAULT 0,
    fee DECIMAL(24,4) NOT NULL DEFAULT 0,
    confirmed_nav DECIMAL(24,8) NULL,
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    source VARCHAR(32) NOT NULL,
    external_reference VARCHAR(128) NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    import_batch_id CHAR(36) NULL,
    conversion_group_id CHAR(36) NULL,
    reverses_transaction_id CHAR(36) NULL,
    note VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by CHAR(36) NOT NULL,
    PRIMARY KEY (transaction_id),
    UNIQUE KEY uk_transaction_idempotency (owner_user_id, portfolio_id, idempotency_key),
    UNIQUE KEY uk_transaction_external_reference (owner_user_id, source, external_reference),
    KEY idx_transaction_portfolio_date (portfolio_id, confirm_date, transaction_id),
    KEY idx_transaction_owner_date (owner_user_id, confirm_date),
    CONSTRAINT fk_transaction_portfolio FOREIGN KEY (portfolio_id) REFERENCES user_portfolio(portfolio_id),
    CONSTRAINT fk_transaction_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id),
    CONSTRAINT fk_transaction_import FOREIGN KEY (import_batch_id) REFERENCES portfolio_import_batch(batch_id),
    CONSTRAINT fk_transaction_reverse FOREIGN KEY (reverses_transaction_id) REFERENCES fund_transaction(transaction_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

