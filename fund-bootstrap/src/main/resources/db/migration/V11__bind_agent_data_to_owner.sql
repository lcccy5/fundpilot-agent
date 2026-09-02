ALTER TABLE agent_conversation ADD COLUMN owner_user_id CHAR(36) NULL AFTER conversation_id;
ALTER TABLE agent_conversation ADD COLUMN created_session_id VARCHAR(128) NULL AFTER owner_user_id;
UPDATE agent_conversation SET owner_user_id='00000000-0000-0000-0000-000000000001' WHERE owner_user_id IS NULL;
ALTER TABLE agent_conversation MODIFY owner_user_id CHAR(36) NOT NULL;
ALTER TABLE agent_conversation ADD KEY idx_conversation_owner_updated (owner_user_id, updated_at);
ALTER TABLE agent_conversation ADD CONSTRAINT fk_conversation_owner FOREIGN KEY (owner_user_id) REFERENCES user_account(user_id);

