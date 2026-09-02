CREATE TABLE watchlist_group (
    group_id CHAR(36) NOT NULL,
    owner_user_id CHAR(36) NOT NULL,
    normalized_name VARCHAR(80) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (group_id),
    UNIQUE KEY uk_watchlist_group_name (owner_user_id, normalized_name),
    KEY idx_watchlist_group_owner (owner_user_id, sort_order),
    CONSTRAINT fk_watchlist_group_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE watchlist_item (
    item_id CHAR(36) NOT NULL,
    group_id CHAR(36) NOT NULL,
    fund_code CHAR(6) NOT NULL,
    note VARCHAR(500) NULL,
    tags_json JSON NULL,
    sort_order INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (item_id),
    UNIQUE KEY uk_watchlist_item_fund (group_id, fund_code),
    KEY idx_watchlist_item_group (group_id, sort_order),
    CONSTRAINT fk_watchlist_item_group FOREIGN KEY (group_id) REFERENCES watchlist_group(group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

