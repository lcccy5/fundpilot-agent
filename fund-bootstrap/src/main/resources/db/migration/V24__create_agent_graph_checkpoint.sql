-- Post-node LangGraph4j snapshots are versioned independently from the outer plan/task lifecycle.
CREATE TABLE agent_graph_checkpoint (
    checkpoint_id CHAR(36) NOT NULL,
    run_id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    graph_name VARCHAR(64) NOT NULL,
    graph_version VARCHAR(32) NOT NULL,
    sequence BIGINT NOT NULL,
    node_name VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    state_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (checkpoint_id),
    UNIQUE KEY uk_graph_checkpoint_task_seq (task_id, sequence),
    KEY idx_graph_checkpoint_resume (run_id, task_id, graph_name, graph_version, sequence),
    CONSTRAINT fk_graph_checkpoint_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_graph_checkpoint_task FOREIGN KEY (task_id) REFERENCES agent_task(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
