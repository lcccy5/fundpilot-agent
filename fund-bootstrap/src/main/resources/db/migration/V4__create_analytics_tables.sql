ALTER TABLE fund
    ADD COLUMN data_revision BIGINT NOT NULL DEFAULT 0 AFTER collected_at,
    ADD COLUMN latest_nav_date DATE NULL AFTER data_revision;

ALTER TABLE fund_nav
    ADD COLUMN adjusted_nav DECIMAL(18,6) NULL AFTER accumulated_nav,
    ADD COLUMN nav_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED' AFTER adjusted_nav;

CREATE TABLE trading_calendar (
    market_code VARCHAR(16) NOT NULL,
    trade_date DATE NOT NULL,
    trading_day TINYINT(1) NOT NULL,
    source_name VARCHAR(64) NOT NULL,
    collected_at DATETIME(3) NOT NULL,
    PRIMARY KEY (market_code, trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE fund_metric_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fund_code VARCHAR(6) NOT NULL,
    period_code VARCHAR(16) NOT NULL,
    actual_start_date DATE NOT NULL,
    actual_end_date DATE NOT NULL,
    nav_basis VARCHAR(32) NOT NULL,
    observation_count INT NOT NULL,
    coverage_rate DECIMAL(10,8) NULL,
    cumulative_return DECIMAL(18,8) NULL,
    annualized_return DECIMAL(18,8) NULL,
    annualized_volatility DECIMAL(18,8) NULL,
    max_drawdown DECIMAL(18,8) NULL,
    sharpe_ratio DECIMAL(18,8) NULL,
    positive_day_ratio DECIMAL(18,8) NULL,
    risk_free_rate DECIMAL(18,8) NOT NULL,
    data_revision BIGINT NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_metric_identity (fund_code, period_code, nav_basis, data_revision, algorithm_version),
    KEY idx_metric_latest (fund_code, period_code, calculated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
