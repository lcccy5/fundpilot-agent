ALTER TABLE agent_fact_card
    ADD COLUMN memory_category VARCHAR(32) NOT NULL DEFAULT 'OTHER' AFTER subject_key;

UPDATE agent_fact_card
SET memory_category = CASE
    WHEN LOWER(tool_name) LIKE '%profile%' THEN 'PROFILE'
    WHEN LOWER(tool_name) LIKE '%realtime%' OR LOWER(tool_name) LIKE '%quote%' THEN 'REALTIME'
    WHEN LOWER(tool_name) LIKE '%metric%' OR LOWER(tool_name) LIKE '%comparison%' THEN 'METRICS'
    WHEN LOWER(tool_name) LIKE '%nav%' THEN 'NAV'
    WHEN LOWER(tool_name) LIKE '%holding%' OR LOWER(tool_name) LIKE '%position%' THEN 'HOLDINGS'
    WHEN LOWER(tool_name) LIKE '%sector%' OR LOWER(tool_name) LIKE '%event%'
        OR LOWER(tool_name) LIKE '%catalyst%' OR LOWER(tool_name) LIKE '%impact%'
        OR LOWER(tool_name) LIKE '%industry%' THEN 'MARKET_SIGNALS'
    WHEN LOWER(tool_name) LIKE '%document%' OR LOWER(tool_name) LIKE '%report%' THEN 'DOCUMENTS'
    WHEN LOWER(tool_name) LIKE '%personal%' OR LOWER(tool_name) LIKE '%portfolio%' THEN 'USER_CONTEXT'
    ELSE 'OTHER'
END;

CREATE TABLE agent_fund_memory_current (
    conversation_id CHAR(36) NOT NULL,
    fund_code VARCHAR(32) NOT NULL,
    memory_category VARCHAR(32) NOT NULL,
    card_id CHAR(36) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id, fund_code, memory_category),
    KEY idx_fund_memory_current_card (card_id),
    CONSTRAINT fk_fund_memory_current_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(conversation_id),
    CONSTRAINT fk_fund_memory_current_card FOREIGN KEY (card_id) REFERENCES agent_fact_card(card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO agent_fund_memory_current(conversation_id,fund_code,memory_category,card_id,updated_at)
SELECT conversation_id,subject_key,memory_category,card_id,created_at
FROM (
    SELECT card_id,conversation_id,subject_key,memory_category,created_at,
           ROW_NUMBER() OVER (PARTITION BY conversation_id,subject_key,memory_category ORDER BY created_at DESC) AS row_number_in_folder
    FROM agent_fact_card
    WHERE subject_key IS NOT NULL AND subject_key <> ''
) ranked
WHERE row_number_in_folder=1;

CREATE TABLE agent_conversation_state (
    conversation_id CHAR(36) NOT NULL,
    active_fund VARCHAR(32) NULL,
    mentioned_funds_json JSON NOT NULL,
    period_start DATE NULL,
    period_end DATE NULL,
    active_topic VARCHAR(32) NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id),
    CONSTRAINT fk_agent_conversation_state_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversation(conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
