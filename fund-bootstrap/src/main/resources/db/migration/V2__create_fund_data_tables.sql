CREATE TABLE IF NOT EXISTS fund (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fund_code VARCHAR(6) NOT NULL,
    fund_name VARCHAR(128) NOT NULL,
    fund_type VARCHAR(64) NULL,
    management_company VARCHAR(128) NULL,
    fund_manager VARCHAR(128) NULL,
    established_date DATE NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    data_source VARCHAR(64) NOT NULL,
    source_updated_at DATETIME(3) NULL,
    collected_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_code (fund_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS fund_nav (
    id BIGINT NOT NULL AUTO_INCREMENT,
    fund_code VARCHAR(6) NOT NULL,
    nav_date DATE NOT NULL,
    unit_nav DECIMAL(18,6) NOT NULL,
    accumulated_nav DECIMAL(18,6) NULL,
    data_source VARCHAR(64) NOT NULL,
    source_updated_at DATETIME(3) NULL,
    collected_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_fund_nav_code_date (fund_code, nav_date),
    KEY idx_fund_nav_date (nav_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

