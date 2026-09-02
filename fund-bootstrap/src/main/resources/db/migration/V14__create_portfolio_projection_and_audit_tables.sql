CREATE TABLE fund_position_snapshot (
    snapshot_id CHAR(36) NOT NULL,
    portfolio_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    fund_code CHAR(6) NOT NULL,
    as_of_date DATE NOT NULL,
    confirmed_shares DECIMAL(24,8) NOT NULL,
    remaining_cost DECIMAL(24,4) NOT NULL,
    realized_profit DECIMAL(24,4) NOT NULL,
    cash_dividend DECIMAL(24,4) NOT NULL,
    last_transaction_id CHAR(36) NULL,
    input_hash CHAR(64) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    nav_data_version VARCHAR(64) NULL,
    coverage_status VARCHAR(32) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_position_snapshot (portfolio_id, fund_code, as_of_date, algorithm_version),
    KEY idx_position_owner_date (owner_user_id, as_of_date),
    CONSTRAINT fk_position_portfolio FOREIGN KEY (portfolio_id) REFERENCES user_portfolio(portfolio_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE portfolio_valuation_snapshot (
    snapshot_id CHAR(36) NOT NULL,
    portfolio_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    as_of_date DATE NOT NULL,
    total_value DECIMAL(24,4) NULL,
    total_cost DECIMAL(24,4) NOT NULL,
    unrealized_profit DECIMAL(24,4) NULL,
    input_hash CHAR(64) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    nav_data_version VARCHAR(64) NULL,
    coverage_status VARCHAR(32) NOT NULL,
    warnings_json JSON NULL,
    calculated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (snapshot_id),
    UNIQUE KEY uk_valuation_snapshot (portfolio_id, as_of_date, algorithm_version),
    KEY idx_valuation_owner_date (owner_user_id, as_of_date),
    CONSTRAINT fk_valuation_portfolio FOREIGN KEY (portfolio_id) REFERENCES user_portfolio(portfolio_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE risk_profile (
    profile_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    questionnaire_version VARCHAR(64) NOT NULL,
    answers_hash CHAR(64) NOT NULL,
    score INT NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (profile_id),
    KEY idx_risk_profile_owner_time (owner_user_id, confirmed_at),
    CONSTRAINT fk_risk_profile_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_audit_log (
    audit_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NULL,
    action VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id_hash CHAR(64) NULL,
    request_id VARCHAR(128) NULL,
    result VARCHAR(32) NOT NULL,
    safe_reason VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (audit_id),
    KEY idx_user_audit_actor_time (actor_user_id, created_at),
    KEY idx_user_audit_action_time (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
