CREATE TABLE user_account (
    user_id CHAR(36) NOT NULL,
    normalized_username VARCHAR(64) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(24) NOT NULL,
    token_version INT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_username (normalized_username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE role (
    role_code VARCHAR(32) NOT NULL,
    description VARCHAR(200) NOT NULL,
    PRIMARY KEY (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_role (
    user_id CHAR(36) NOT NULL,
    role_code VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_user_role_account FOREIGN KEY (user_id) REFERENCES user_account(user_id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_code) REFERENCES role(role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE refresh_token (
    token_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    family_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    replaced_by_token_id CHAR(36) NULL,
    created_ip_hash CHAR(64) NULL,
    user_agent_hash CHAR(64) NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (token_id),
    UNIQUE KEY uk_refresh_hash (token_hash),
    KEY idx_refresh_user_family (user_id, family_id),
    KEY idx_refresh_expiry (expires_at),
    CONSTRAINT fk_refresh_user FOREIGN KEY (user_id) REFERENCES user_account(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO role(role_code,description) VALUES
('USER','Personal research user'),('ANALYST','Research operator'),('ADMIN','Platform administrator');

INSERT INTO user_account(user_id,normalized_username,display_name,password_hash,status,token_version,created_at,updated_at)
VALUES('00000000-0000-0000-0000-000000000001','legacy-local','Legacy local data','!','LEGACY',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3));

